/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2016 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.lenskit.data.store;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Longs;
import com.google.common.reflect.TypeToken;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.lenskit.data.entities.*;
import org.lenskit.util.BinarySearch;

/**
 * Entity collection builder packing data into shards.
 */
class PackedEntityCollectionBuilder extends EntityCollectionBuilder {
    private final EntityType entityType;
    private final AttributeSet attributes;
    private final LongAttrStoreBuilder idStore;
    private final AttrStoreBuilder[] storeBuilders;
    private final PackIndex.Builder[] indexBuilders;
    private LongSet ids = null;
    private boolean isSorted = true;
    private int size = 0;
    private long lastEntityId = Long.MIN_VALUE;

    PackedEntityCollectionBuilder(EntityType et, AttributeSet attrs) {
        Preconditions.checkArgument(attrs.size() > 0, "attribute set is emtpy");
        Preconditions.checkArgument(attrs.size() < 32, "cannot have more than 31 attributes");
        Preconditions.checkArgument(attrs.getAttribute(0) == CommonAttributes.ENTITY_ID,
                                    "attribute set does not contain entity ID attribute");
        entityType = et;
        attributes = attrs;
        int n = attrs.size();
        storeBuilders = new AttrStoreBuilder[n];
        indexBuilders = new PackIndex.Builder[n];
        idStore = new LongAttrStoreBuilder();
        storeBuilders[0] = idStore;
        for (int i = 1; i < n; i++) {
            TypedName<?> attr = attrs.getAttribute(i);
            AttrStoreBuilder asb;
            if (attr.getType().equals(TypeToken.of(Long.class))) {
                asb = new AttrStoreBuilder(LongShard::create);
            } else if (attr.getType().equals(TypeToken.of(Integer.class))) {
                asb = new AttrStoreBuilder(IntShard::create);
            } else if (attr.getType().equals(TypeToken.of(Double.class))) {
                asb = new AttrStoreBuilder(DoubleShard::create);
            } else {
                asb = new AttrStoreBuilder(ObjectShard::new);
            }
            storeBuilders[i] = asb;
        }
    }

    @Override
    public <T> EntityCollectionBuilder addIndex(TypedName<T> attribute) {
        int pos = attributes.lookup(attribute);
        if (pos >= 0) {
            addIndex(pos);
        }
        return this;
    }

    @Override
    public EntityCollectionBuilder addIndex(String attrName) {
        int pos = attributes.lookup(attrName);
        if (pos >= 0) {
            addIndex(pos);
        }
        return this;
    }

    private void addIndex(int aidx) {
        TypedName<?> tn = attributes.getAttribute(aidx);
        PackIndex.Builder builder;
        if (tn.getRawType().equals(Long.class)) {
            indexBuilders[aidx] = builder = new PackIndex.LongBuilder();
        } else {
            indexBuilders[aidx] = builder = new PackIndex.GenericBuilder();
        }
        for (int i = 0; i < size; i++) {
            builder.add(storeBuilders[aidx].get(i), i);
        }
    }

    @Override
    public EntityCollectionBuilder add(Entity e, boolean replace) {
        long id = e.getId();
        isSorted &= id > lastEntityId;

        if (!isSorted) {
            if (ids == null) {
                ids = new LongOpenHashSet();
                for (int i = 0; i < size; i++) {
                    ids.add((long) storeBuilders[0].get(i));
                }
            }

            if (ids.contains(id)) {
                if (replace) {
                    throw new UnsupportedOperationException("packed builder cannot replace entities");
                } else {
                    return this; // don't replace existing id
                }
            }
        } else if (!replace) {
            BinarySearch search = new IdSearch(id);
            int res = search.search(0, size);
            if (res <= 0) {
                return this;
            }
        }

        for (Attribute<?> a: e.getAttributes()) {
            int ap = attributes.lookup(a.getTypedName());
            if (ap >= 0) {
                storeBuilders[ap].add(a.getValue());
            }
            if (indexBuilders[ap] != null) {
                indexBuilders[ap].add(a.getValue(), size);
            }
        }
        size += 1;
        lastEntityId = id;

        for (AttrStoreBuilder storeBuilder : storeBuilders) {
            if (storeBuilder.size() < size) {
                assert storeBuilder.size() == size - 1;
                storeBuilder.skip();
            }
        }

        return this;
    }

    @Override
    public Iterable<Entity> entities() {
        AttrStore[] stores = new AttrStore[storeBuilders.length];
        for (int i = 0; i < stores.length; i++) {
            stores[i] = storeBuilders[i].build();
        }
        // the packed collection is not fully functional! But it will be iterable.
        return new PackedEntityCollection(entityType, attributes, stores, new PackIndex[indexBuilders.length]);
    }

    @Override
    public EntityCollection build() {
        if (!isSorted) {
            throw new IllegalStateException("cannot yet support unsorted builds");
        }
        AttrStore[] stores = new AttrStore[storeBuilders.length];
        PackIndex[] indexes = new PackIndex[indexBuilders.length];
        for (int i = 0; i < stores.length; i++) {
            stores[i] = storeBuilders[i].build();
            if (indexBuilders[i] != null) {
                indexes[i] = indexBuilders[i].build();
            }
        }
        return new PackedEntityCollection(entityType, attributes, stores, indexes);
    }

    private class IdSearch extends BinarySearch {
        private final long targetId;

        IdSearch(long id) {
            targetId = id;
        }

        @Override
        protected int test(int pos) {
            return Longs.compare(targetId, idStore.getLong(pos));
        }
    }
}
