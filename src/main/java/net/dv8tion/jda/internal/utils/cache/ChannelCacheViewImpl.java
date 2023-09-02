/*
 * Copyright 2015 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.internal.utils.cache;

import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.utils.ClosableIterator;
import net.dv8tion.jda.api.utils.LockIterator;
import net.dv8tion.jda.api.utils.MiscUtil;
import net.dv8tion.jda.api.utils.cache.ChannelCacheView;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.Helpers;
import net.dv8tion.jda.internal.utils.UnlockHook;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ChannelCacheViewImpl<T extends Channel> extends ReadWriteLockCache<T> implements ChannelCacheView<T>
{
    protected final EnumMap<ChannelType, TLongObjectMap<T>> caches = new EnumMap<>(ChannelType.class);

    public ChannelCacheViewImpl(Class<T> type)
    {
        for (ChannelType channelType : ChannelType.values())
        {
            channelType = normalizeKey(channelType);
            Class<? extends Channel> clazz = channelType.getInterface();
            if (channelType != ChannelType.UNKNOWN && type.isAssignableFrom(clazz))
                caches.put(channelType, new TLongObjectHashMap<>());
        }
    }

    // Store all threads under the same channel type, makes it easier because the interface is shared
    protected ChannelType normalizeKey(ChannelType type)
    {
        return type.isThread() ? ChannelType.GUILD_PUBLIC_THREAD : type;
    }

    @SuppressWarnings("unchecked")
    protected <C extends T> TLongObjectMap<C> getMap(@Nonnull ChannelType type)
    {
        return (TLongObjectMap<C>) caches.get(normalizeKey(type));
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public <C extends T> C put(C element)
    {
        try (UnlockHook hook = writeLock())
        {
            return (C) getMap(element.getType()).put(element.getIdLong(), element);
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public <C extends T> C remove(ChannelType type, long id)
    {
        try (UnlockHook hook = writeLock())
        {
            T removed = getMap(type).remove(id);
            return (C) removed;
        }
    }

    public void clear()
    {
        try (UnlockHook hook = writeLock())
        {
            caches.values().forEach(TLongObjectMap::clear);
        }
    }

    @Nonnull
    @Override
    public <C extends T> ChannelCacheView<C> ofType(@Nonnull Class<C> type)
    {
        return new FilteredCacheView<>(type);
    }

    @Override
    public void forEach(Consumer<? super T> action)
    {
        try (UnlockHook hook = readLock())
        {
            for (TLongObjectMap<T> cache : caches.values())
            {
                cache.valueCollection().forEach(action);
            }
        }
    }

    @Nonnull
    @Override
    public List<T> asList()
    {
        List<T> list = getCachedList();
        if (list == null)
            list = cache((List<T>) applyStream(stream -> stream.collect(Collectors.toList())));
        return list;
    }

    @Nonnull
    @Override
    public Set<T> asSet()
    {
        Set<T> set = getCachedSet();
        if (set == null)
            set = cache((Set<T>) applyStream(stream -> stream.collect(Collectors.toSet())));
        return set;
    }

    @Nonnull
    @Override
    public ClosableIterator<T> lockedIterator()
    {
        ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
        MiscUtil.tryLock(readLock);
        try
        {
            Iterator<? extends T> directIterator = caches.values().stream().flatMap(map -> map.valueCollection().stream()).iterator();
            return new LockIterator<>(directIterator, readLock);
        }
        catch (Throwable t)
        {
            readLock.unlock();
            throw t;
        }
    }

    @Override
    public long size()
    {
        try (UnlockHook hook = readLock())
        {
            return caches.values().stream().mapToLong(TLongObjectMap::size).sum();
        }
    }

    @Override
    public boolean isEmpty()
    {
        try (UnlockHook hook = readLock())
        {
            return caches.values().stream().allMatch(TLongObjectMap::isEmpty);
        }
    }

    @Nonnull
    @Override
    public List<T> getElementsByName(@Nonnull String name, boolean ignoreCase)
    {
        Checks.notEmpty(name, "Name");
        return applyStream(stream ->
            stream
                .filter((channel) -> Helpers.equals(channel.getName(), name, ignoreCase))
                .collect(Collectors.toList())
        );
    }

    @Nonnull
    @Override
    public Stream<T> stream()
    {
        return this.asList().stream();
    }

    @Nonnull
    @Override
    public Stream<T> parallelStream()
    {
        return this.asList().parallelStream();
    }

    @Nullable
    @Override
    public T getElementById(long id)
    {
        try (UnlockHook hook = readLock())
        {
            for (TLongObjectMap<? extends T> cache : caches.values())
            {
                T element = cache.get(id);
                if (element != null)
                    return element;
            }
            return null;
        }
    }

    @Nonnull
    @Override
    public Iterator<T> iterator()
    {
        return stream().iterator();
    }

    protected class FilteredCacheView<C extends T> implements ChannelCacheView<C>
    {
        protected final Class<C> type;
        protected final ChannelType concreteType;

        protected FilteredCacheView(Class<C> type)
        {
            Checks.notNull(type, "Channel Type");
            this.type = type;
            ChannelType concrete = null;
            for (ChannelType channelType : ChannelType.values())
            {
                channelType = normalizeKey(channelType);
                if (channelType != ChannelType.UNKNOWN && type.equals(channelType.getInterface()))
                {
                    concrete = channelType;
                    break;
                }
            }
            this.concreteType = concrete;
        }

        @Nonnull
        @Override
        public List<C> asList()
        {
            return applyStream(stream -> stream.collect(Helpers.toUnmodifiableList()));
        }

        @Nonnull
        @Override
        public Set<C> asSet()
        {
            return applyStream(stream ->
                stream.collect(
                    Collectors.collectingAndThen(
                        Collectors.toSet(),
                        Collections::unmodifiableSet))
            );
        }

        @Nonnull
        @Override
        @SuppressWarnings("unchecked")
        public ClosableIterator<C> lockedIterator()
        {
            ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
            MiscUtil.tryLock(readLock);
            try
            {
                Iterator<? extends C> directIterator = concreteType != null
                        ? (Iterator<? extends C>) getMap(concreteType).valueCollection().iterator()
                        : caches.entrySet()
                            .stream()
                            .filter((entry) -> type.isAssignableFrom(entry.getKey().getInterface()))
                            .map(Map.Entry::getValue)
                            .flatMap(map -> map.valueCollection().stream())
                            .filter(type::isInstance)
                            .map(type::cast)
                            .iterator();
                return new LockIterator<>(directIterator, readLock);
            }
            catch (Throwable t)
            {
                readLock.unlock();
                throw t;
            }
        }

        @Override
        public long size()
        {
            try (UnlockHook hook = readLock())
            {
                return concreteType != null ? getMap(concreteType).size() : applyStream(Stream::count);
            }
        }

        @Override
        public boolean isEmpty()
        {
            try (UnlockHook hook = readLock())
            {
                return concreteType != null ? getMap(concreteType).isEmpty() : applyStream(stream -> !stream.findAny().isPresent());
            }
        }

        @Nonnull
        @Override
        public List<C> getElementsByName(@Nonnull String name, boolean ignoreCase)
        {
            Checks.notEmpty(name, "Name");
            return applyStream(stream ->
                stream
                    .filter(channel -> Helpers.equals(channel.getName(), name, ignoreCase))
                    .collect(Collectors.toList())
            );
        }

        @Nonnull
        @Override
        public Stream<C> stream()
        {
            return asList().stream();
        }

        @Nonnull
        @Override
        public Stream<C> parallelStream()
        {
            return asList().parallelStream();
        }

        @Nonnull
        @Override
        public <C1 extends C> ChannelCacheView<C1> ofType(@Nonnull Class<C1> type)
        {
            return ChannelCacheViewImpl.this.ofType(type);
        }

        @Nullable
        @Override
        public C getElementById(long id)
        {
            try (UnlockHook hook = readLock())
            {
                if (concreteType != null)
                    return type.cast(getMap(concreteType).get(id));

                T element = ChannelCacheViewImpl.this.getElementById(id);
                if (type.isInstance(element))
                    return type.cast(element);
                return null;
            }
        }

        @Nonnull
        @Override
        public Iterator<C> iterator()
        {
            return asList().iterator();
        }
    }
}
