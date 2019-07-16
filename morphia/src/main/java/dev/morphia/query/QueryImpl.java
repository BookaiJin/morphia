package dev.morphia.query;


import com.mongodb.WriteConcern;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.result.DeleteResult;
import dev.morphia.Datastore;
import dev.morphia.DeleteOptions;
import dev.morphia.FindAndModifyOptions;
import dev.morphia.Key;
import dev.morphia.annotations.Entity;
import dev.morphia.internal.PathTarget;
import dev.morphia.mapping.MappedClass;
import dev.morphia.mapping.Mapper;
import dev.morphia.query.internal.MorphiaCursor;
import dev.morphia.query.internal.MorphiaKeyCursor;
import org.bson.Document;
import org.bson.types.CodeWScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.mongodb.CursorType.NonTailable;
import static dev.morphia.query.CriteriaJoin.AND;
import static java.lang.String.format;


/**
 * Implementation of Query
 *
 * @param <T> The type we will be querying for, and returning.
 */
@SuppressWarnings("removal")
public class QueryImpl<T> implements CriteriaContainer, Query<T> {
    private static final Logger LOG = LoggerFactory.getLogger(QueryImpl.class);
    final Datastore ds;
    final MongoCollection<T> collection;
    final Class<T> clazz;
    final Mapper mapper;
    private boolean validateName = true;
    private boolean validateType = true;
    private Document baseQuery;
    private FindOptions options;
    private CriteriaContainer compoundContainer;

    FindOptions getOptions() {
        if (options == null) {
            options = new FindOptions();
        }
        return options;
    }

    /**
     * Creates a Query for the given type and collection
     *  @param clazz the type to return
     * @param coll  the collection to query
     * @param ds    the Datastore to use
     */
    public QueryImpl(final Class<T> clazz, final MongoCollection<T> coll, final Datastore ds) {
        this.clazz = clazz;
        this.ds = ds;
        collection = coll;
        mapper = this.ds.getMapper();

        compoundContainer = new CriteriaContainerImpl(mapper, this, AND);
    }

    @Override
    public MorphiaKeyCursor<T> keys() {
        return keys(new FindOptions());
    }

    @Override
    public MorphiaKeyCursor<T> keys(final FindOptions options) {
        QueryImpl<T> cloned = cloneQuery();
        options.projection().include("_id");

        return new MorphiaKeyCursor<>(cloned.prepareCursor(options), ds.getMapper(),
            clazz, collection.getNamespace().getCollectionName());
    }

    @Override
    public long count() {
        return collection.count(getQueryDocument());
    }

    @Override
    public long count(final CountOptions options) {
        return collection.count(getQueryDocument(), options);
    }

    @Override
    public MorphiaCursor<T> execute() {
        return this.execute(getOptions());
    }

    @Override
    public MorphiaCursor<T> execute(final FindOptions options) {
        MongoCursor mongoCursor = prepareCursor(options);
        return new MorphiaCursor<>(mongoCursor);
    }

    @Override
    public T first() {
        try (MongoCursor<T> iterator = this.execute()) {
            return iterator.tryNext();
        }
    }

    @Override
    public T first(final FindOptions options) {
        try (MongoCursor<T> it = this.execute(options.copy().limit(1))) {
            return it.tryNext();
        }
    }

    @Override
    public Key<T> getKey() {
        return getKey(getOptions());
    }

    @Override
    public Key<T> getKey(final FindOptions options) {
        try (MongoCursor<Key<T>> it = keys(options.copy().limit(1))) {
            return it.tryNext();
        }
    }

    /**
     * @morphia.internal
     */
    QueryImpl<T> cloneQuery() {
        final QueryImpl<T> n = new QueryImpl<>(clazz, collection, ds);
        n.validateName = validateName;
        n.validateType = validateType;
        n.baseQuery = copy(baseQuery);
        n.options = options != null ? options.copy() : null;
        n.compoundContainer = compoundContainer;
        return n;
    }

    private Document copy(final Document document) {
        return document == null ? null : new Document(document);
    }

    @Override
    public FieldEnd<? extends CriteriaContainer> criteria(final String field) {
        final CriteriaContainerImpl container = new CriteriaContainerImpl(mapper, this, AND);
        add(container);

        return new FieldEndImpl<CriteriaContainer>(mapper, this, field, container);
    }

    @Override
    public Query<T> disableValidation() {
        validateName = false;
        validateType = false;
        return this;
    }

    @Override
    public Query<T> enableValidation() {
        validateName = true;
        validateType = true;
        return this;
    }

    @Override
    public Map<String, Object> explain() {
        return explain(new FindOptions());
    }

    @Override
    public Map<String, Object> explain(final FindOptions options) {
        return new LinkedHashMap<>(ds.getDatabase()
                                     .runCommand(new Document("explain",
                                         new Document("find", collection.getNamespace().getCollectionName())
                                             .append("filter", getQueryDocument()))));
    }


    @Override
    public FieldEnd<? extends Query<T>> field(final String name) {
        return new FieldEndImpl<>(mapper, this, name, this);
    }

    @Override
    public Query<T> filter(final String condition, final Object value) {
        final String[] parts = condition.trim().split(" ");
        if (parts.length < 1 || parts.length > 6) {
            throw new IllegalArgumentException("'" + condition + "' is not a legal filter condition");
        }

        final String prop = parts[0].trim();
        final FilterOperator op = (parts.length == 2) ? translate(parts[1]) : FilterOperator.EQUAL;

        add(new FieldCriteria(mapper, this, prop, op, value));

        return this;
    }

    /**
     * @return the collection this query targets
     * @morphia.internal
     */
    public MongoCollection getCollection() {
        return collection;
    }

    /**
     * @return the entity {@link Class}.
     * @morphia.internal
     */
    public Class<T> getEntityClass() {
        return clazz;
    }

    /**
     * @return the Mongo fields {@link Document}.
     * @morphia.internal
     */
    public Document getFieldsObject() {
        Projection projection = getOptions().getProjection();

        Document document = projection.map(mapper, clazz);
        if(document != null) {
            final MappedClass mc = ds.getMapper().getMappedClass(clazz);

            Entity entityAnnotation = mc.getEntityAnnotation();

            if (projection.isIncluding() && entityAnnotation != null && entityAnnotation.useDiscriminator()) {
                document.put(ds.getMapper().getOptions().getDiscriminatorField(), 1);
            }
        }

        return document;
    }

    /**
     * @return the query object
     * @morphia.internal
     */
    public Document getQueryDocument() {
        final Document obj = new Document();

        if (baseQuery != null) {
            obj.putAll(baseQuery);
        }

        obj.putAll(toDocument());

        return obj;
    }

    /**
     * Sets query structure directly
     *
     * @param query the Document containing the query
     */
    public void setQueryObject(final Document query) {
        baseQuery = new Document(query);
    }

    /**
     * @return the Mongo sort {@link Document}.
     * @morphia.internal
     */
    public Document getSort() {
        return options != null ? options.getSort() : null;
    }

    @Override
    public Query<T> order(final Meta sort) {
        getOptions().sort(sort.toDatabase());

        return this;
    }

    @Override
    public Query<T> order(final Sort... sorts) {
        Document sortList = new Document();
        for (Sort sort : sorts) {
            String s = sort.getField();
            if (validateName) {
                s = new PathTarget(ds.getMapper(), clazz, s).translatedPath();
            }
            sortList.put(s, sort.getOrder());
        }
        getOptions().sort(sortList);
        return this;
    }

    @Override
    public Query<T> project(final String field, final boolean include) {
        Projection projection = getOptions().projection();
        if(include) {
            projection.include(field);
        } else {
            projection.exclude(field);
        }

        return this;
    }

    @Override
    public Query<T> project(final String field, final ArraySlice slice) {
        getOptions().projection().project(field, slice);
        return this;
    }

    @Override
    public Query<T> project(final Meta meta) {
        getOptions().projection().project(meta);
        return this;
    }

    @Override
    public Query<T> retrieveKnownFields() {
        getOptions().projection().knownFields();
        return this;
    }

    @Override
    public Query<T> search(final String search) {

        final Document op = new Document("$search", search);

        this.criteria("$text").equal(op);

        return this;
    }

    @Override
    public Query<T> search(final String search, final String language) {

        final Document op = new Document("$search", search)
                                     .append("$language", language);

        this.criteria("$text").equal(op);

        return this;
    }

    @Override
    public Query<T> where(final String js) {
        add(new WhereCriteria(js));
        return this;
    }

    @Override
    public Query<T> where(final CodeWScope js) {
        add(new WhereCriteria(js));
        return this;
    }

    public T delete() {
        return delete(new FindAndDeleteOptions());
    }

    @Override
    public T delete(final FindAndModifyOptions options) {
        return delete(new FindAndDeleteOptions()
                          .writeConcern(options.getWriteConcern())
                          .collation(options.getCollation())
                          .maxTime(options.getMaxTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
                          .sort(options.getSort())
                          .projection(options.getProjection()));
    }

    public T delete(final FindAndDeleteOptions options) {
        return ds.enforceWriteConcern(collection, clazz, options.getWriteConcern())
                   .findOneAndDelete(getQueryDocument(), options);
    }

    @Override
    public DeleteResult remove(final DeleteOptions options) {
        MongoCollection<T> collection = enforceWriteConcern(clazz, options.getWriteConcern());
        return options.isMulti()
               ? collection.deleteMany(getQueryDocument(), options)
               : collection.deleteOne(getQueryDocument(), options);
    }

    @Override
    public Modify<T> modify() {
        return new Modify<>(this);
    }

    @Override
    public Modify<T> modify(final UpdateOperations<T> operations) {
        Modify<T> modify = modify();
        modify.setOps(((UpdateOpsImpl) operations).getOps());

        return modify;
    }

    @Override
    public Update<T> update() {
        return new Update<>(ds, mapper, clazz, collection, getQueryDocument());
    }

    @Override
    @Deprecated(since = "2.0", forRemoval = true)
    public Update<T> update(UpdateOperations operations) {
        final Update<T> updates = update();
        updates.setOps(((UpdateOpsImpl) operations).getOps());

        return updates;
    }

    @Override
    @Deprecated(since = "2.0", forRemoval = true)
    public Update<T> update(Document document) {
        final Update<T> updates = update();
        updates.setOps(document);

        return updates;
    }

    @Override
    public String getFieldName() {
        throw new UnsupportedOperationException("this method is unused on a Query");
    }

    /**
     * @return true if field names are being validated
     */
    public boolean isValidatingNames() {
        return validateName;
    }

    private MongoCursor prepareCursor(final FindOptions findOptions) {
        final Document query = getQueryDocument();

        if (LOG.isTraceEnabled()) {
            LOG.trace(format("Running query(%s) : %s, options: %s,", collection.getNamespace().getCollectionName(), query, findOptions));
        }

        if (findOptions.getCursorType() != NonTailable && (findOptions.getSort() != null)) {
            LOG.warn("Sorting on tail is not allowed.");
        }

        FindIterable<T> iterable = collection.find(query);

        return findOptions.apply(iterable).iterator();
    }

    @Override
    public String toString() {
        return getOptions().getProjection() == null ? getQueryDocument().toString()
                                                    : format("{ %s,  %s }", getQueryDocument(), getFieldsObject());
    }

    /**
     * Converts the textual operator (">", "<=", etc) into a FilterOperator. Forgiving about the syntax; != and <> are NOT_EQUAL, = and ==
     * are EQUAL.
     */
    private FilterOperator translate(final String operator) {
        return FilterOperator.fromString(operator);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QueryImpl)) {
            return false;
        }

        final QueryImpl<?> query = (QueryImpl<?>) o;

        if (validateName != query.validateName) {
            return false;
        }
        if (validateType != query.validateType) {
            return false;
        }
        if (getCollection() != null ? !getCollection().equals(query.getCollection()) : query.getCollection() != null) {
            return false;
        }
        if (clazz != null ? !clazz.equals(query.clazz) : query.clazz != null) {
            return false;
        }
        if (baseQuery != null ? !baseQuery.equals(query.baseQuery) : query.baseQuery != null) {
            return false;
        }
        if (getOptions() != null ? !getOptions().equals(query.getOptions()) : query.getOptions() != null) {
            return false;
        }
        return compoundContainer != null ? compoundContainer.equals(query.compoundContainer) : query.compoundContainer == null;
    }

    @Override
    public int hashCode() {
        int result = getCollection() != null ? getCollection().hashCode() : 0;
        result = 31 * result + (clazz != null ? clazz.hashCode() : 0);
        result = 31 * result + (validateName ? 1 : 0);
        result = 31 * result + (validateType ? 1 : 0);
        result = 31 * result + (baseQuery != null ? baseQuery.hashCode() : 0);
        result = 31 * result + (getOptions() != null ? getOptions().hashCode() : 0);
        result = 31 * result + (compoundContainer != null ? compoundContainer.hashCode() : 0);
        return result;
    }

    @Override
    public void add(final Criteria... criteria) {
        for (final Criteria c : criteria) {
            c.attach(this);
            compoundContainer.add(c);
        }
    }

    @Override
    public CriteriaContainer and(final Criteria... criteria) {
        return compoundContainer.and(criteria);
    }

    @Override
    public CriteriaContainer or(final Criteria... criteria) {
        return compoundContainer.or(criteria);
    }

    @Override
    public Document toDocument() {
        return compoundContainer.toDocument();
    }

    @Override
    public void remove(final Criteria criteria) {
        compoundContainer.remove(criteria);
    }

    @Override
    public void attach(final CriteriaContainer container) {
        compoundContainer.attach(container);
    }

    private MongoCollection<T> enforceWriteConcern(final Class<T> klass, final WriteConcern writeConcern) {
        WriteConcern concern = writeConcern;
        if (concern == null) {
            concern = getWriteConcern(klass);
        }
        return concern == null ? collection : collection.withWriteConcern(concern);
    }

    WriteConcern getWriteConcern(final Object clazzOrEntity) {
        WriteConcern wc = ds.getMongo().getWriteConcern();
        if (clazzOrEntity != null) {
            final Entity entityAnn = mapper.getMappedClass(clazzOrEntity).getEntityAnnotation();
            if (entityAnn != null && !entityAnn.concern().isEmpty()) {
                wc = WriteConcern.valueOf(entityAnn.concern());
            }
        }

        return wc;
    }

}
