/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.index.sai.disk.format;

public enum IndexComponent
{
    /**
     * Stores per-index metadata.
     */
    META("Meta"),
    /**
     * KDTree written by {@code BKDWriter} indexes mappings of term to one ore more segment row IDs
     * (segment row ID = SSTable row ID - segment row ID offset).
     */
    KD_TREE("KDTree"),
    KD_TREE_POSTING_LISTS("KDTreePostingLists"),
    ROW_ID_POINT_ID_MAP("RowIDPointIDMap"),
    /**
     * Term dictionary written by {@code TrieTermsDictionaryWriter} stores mappings of term and
     * file pointer to posting block on posting file.
     */
    TERMS_DATA("TermsData"),
    COMPRESSED_TERMS_DATA("CompressedTermsData"),
    TERMS_INDEX("TermsIndex"),
    ORDER_MAP("OrderMap"),
    /**
     * Stores postings written by {@code PostingsWriter}
     */
    POSTING_LISTS("PostingLists"),
    /**
     * If present indicates that the column index build completed successfully
     */
    COLUMN_COMPLETION_MARKER("ColumnComplete"),

    // per-sstable components
    /**
     * A list of primary keys in the sstable, one for each row, ordered by row ids
     */
    PRIMARY_KEYS("PrimaryKeys"),
    /**
     * A list of byte offsets into the PrimaryKeys component, in the same order as primary keys.
     * Allows finding the primary key by row id.
     */
    PRIMARY_KEY_OFFSETS("PrimaryKeyOffsets"),
    /**
     * Partition key token value for rows including row tombstone and static row. (access key is rowId)
     */
    TOKEN_VALUES("TokenValues"),
    /**
     * Partition key offset in sstable data file for rows including row tombstone and static row. (access key is
     * rowId)
     */
    OFFSETS_VALUES("OffsetsValues"),
    /**
     * Stores per-sstable metadata.
     */
    GROUP_META("GroupMeta"),
    /**
     * If present indicates that the per-sstable index build completed successfully
     */
    GROUP_COMPLETION_MARKER("GroupComplete");

    public final String representation;

    IndexComponent(String representation)
    {
        this.representation = representation;
    }

    static IndexComponent fromRepresentation(String representation)
    {
        for (IndexComponent indexComponent : values())
        {
            if (indexComponent.representation != null && indexComponent.representation.equals(representation))
                return indexComponent;
        }
        return null;
    }

//    public static final IndexComponent GROUP_COMPLETION_MARKER = new IndexComponent(Type.GROUP_COMPLETION_MARKER);
//    public static final IndexComponent GROUP_META = new IndexComponent(Type.GROUP_META);
//    public static final IndexComponent OFFSETS_VALUES = new IndexComponent(Type.OFFSETS_VALUES);
//    public static final IndexComponent TOKEN_VALUES = new IndexComponent(Type.TOKEN_VALUES);
//
//    public static final Set<IndexComponent> PER_SSTABLE = Sets.newHashSet(GROUP_COMPLETION_MARKER, GROUP_META, TOKEN_VALUES, OFFSETS_VALUES);
//    public static final Set<IndexComponent.Type> PER_INDEX_TYPES = Sets.newHashSet(Type.COLUMN_COMPLETION_MARKER,
//                                                                                   Type.META,
//                                                                                   Type.KD_TREE,
//                                                                                   Type.KD_TREE_POSTING_LISTS,
//                                                                                   Type.TERMS_DATA,
//                                                                                   Type.POSTING_LISTS);
//
//    public final Type type;
//    public final String index;
//
//    private IndexComponent(Type type)
//    {
//        this.type = type;
//        this.index = null;
//    }
//
//    private IndexComponent(Type type, String index)
//    {
//        this.type = type;
//        this.index = index;
//    }
//
//    public static IndexComponent create(Type type)
//    {
//        IndexComponent component;
//        switch (type)
//        {
//            case GROUP_COMPLETION_MARKER:
//                component = GROUP_COMPLETION_MARKER;
//                break;
//            case GROUP_META:
//                component = GROUP_META;
//                break;
//            case OFFSETS_VALUES:
//                component = OFFSETS_VALUES;
//                break;
//            case TOKEN_VALUES:
//                component = TOKEN_VALUES;
//                break;
//            default:
//                throw new AssertionError();
//        }
//        return component;
//    }
//
//    public static IndexComponent create(Type type, String index)
//    {
//        IndexComponent component;
//        switch (type)
//        {
//            case COLUMN_COMPLETION_MARKER:
//            case META :
//            case KD_TREE:
//            case KD_TREE_POSTING_LISTS:
//            case TERMS_DATA:
//            case POSTING_LISTS:
//                component = new IndexComponent(type, index);
//                break;
//            default: throw new AssertionError();
//        }
//        return component;
//    }
//
//    public static IndexComponent parse(String name, String index)
//    {
//        Type type = Type.fromRepresentation(name);
//
//        switch (type)
//        {
//            case GROUP_COMPLETION_MARKER: return GROUP_COMPLETION_MARKER;
//            case GROUP_META: return GROUP_META;
//            case OFFSETS_VALUES: return OFFSETS_VALUES;
//            case TOKEN_VALUES: return TOKEN_VALUES;
//            case COLUMN_COMPLETION_MARKER:
//            case META :
//            case KD_TREE:
//            case KD_TREE_POSTING_LISTS:
//            case TERMS_DATA:
//            case POSTING_LISTS: return new IndexComponent(type, index);
//            default: throw new AssertionError();
//        }
//    }
//
//    @Override
//    public int hashCode()
//    {
//        return Objects.hashCode(type, index);
//    }
//
//    @Override
//    public boolean equals(Object o)
//    {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        IndexComponent component = (IndexComponent)o;
//        return Objects.equal(type, component.type) && Objects.equal(index, component.index);
//    }
}
