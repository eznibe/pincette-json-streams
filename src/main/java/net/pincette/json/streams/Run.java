package net.pincette.json.streams;

import static com.mongodb.client.model.Filters.eq;
import static java.lang.Runtime.getRuntime;
import static java.lang.System.exit;
import static java.time.Duration.ofSeconds;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Stream.concat;
import static net.pincette.jes.Aggregate.reducer;
import static net.pincette.jes.elastic.Logging.log;
import static net.pincette.jes.util.JsonFields.ID;
import static net.pincette.jes.util.Kafka.createReliableProducer;
import static net.pincette.jes.util.Kafka.fromConfig;
import static net.pincette.jes.util.Mongo.withResolver;
import static net.pincette.jes.util.Util.compose;
import static net.pincette.jes.util.Validation.validator;
import static net.pincette.json.Factory.f;
import static net.pincette.json.Factory.o;
import static net.pincette.json.Factory.v;
import static net.pincette.json.Jslt.tryTransformer;
import static net.pincette.json.JsonUtil.asString;
import static net.pincette.json.JsonUtil.createObjectBuilder;
import static net.pincette.json.JsonUtil.from;
import static net.pincette.json.JsonUtil.getObjects;
import static net.pincette.json.JsonUtil.getStrings;
import static net.pincette.json.JsonUtil.getValue;
import static net.pincette.json.JsonUtil.isString;
import static net.pincette.json.JsonUtil.toNative;
import static net.pincette.json.streams.Common.AGGREGATE;
import static net.pincette.json.streams.Common.AGGREGATE_TYPE;
import static net.pincette.json.streams.Common.APPLICATION_FIELD;
import static net.pincette.json.streams.Common.COMMANDS;
import static net.pincette.json.streams.Common.DESTINATIONS;
import static net.pincette.json.streams.Common.DESTINATION_TYPE;
import static net.pincette.json.streams.Common.DOLLAR;
import static net.pincette.json.streams.Common.DOT;
import static net.pincette.json.streams.Common.ENVIRONMENT;
import static net.pincette.json.streams.Common.EVENT_TO_COMMAND;
import static net.pincette.json.streams.Common.FILTER;
import static net.pincette.json.streams.Common.FROM_STREAM;
import static net.pincette.json.streams.Common.FROM_STREAMS;
import static net.pincette.json.streams.Common.FROM_TOPIC;
import static net.pincette.json.streams.Common.FROM_TOPICS;
import static net.pincette.json.streams.Common.JOIN;
import static net.pincette.json.streams.Common.JSLT_IMPORTS;
import static net.pincette.json.streams.Common.LEFT;
import static net.pincette.json.streams.Common.MERGE;
import static net.pincette.json.streams.Common.NAME;
import static net.pincette.json.streams.Common.ON;
import static net.pincette.json.streams.Common.PARTS;
import static net.pincette.json.streams.Common.PIPELINE;
import static net.pincette.json.streams.Common.REACTOR;
import static net.pincette.json.streams.Common.REDUCER;
import static net.pincette.json.streams.Common.RIGHT;
import static net.pincette.json.streams.Common.SLASH;
import static net.pincette.json.streams.Common.SOURCE_TYPE;
import static net.pincette.json.streams.Common.STREAM;
import static net.pincette.json.streams.Common.TYPE;
import static net.pincette.json.streams.Common.VALIDATOR;
import static net.pincette.json.streams.Common.VERSION;
import static net.pincette.json.streams.Common.WINDOW;
import static net.pincette.json.streams.Common.build;
import static net.pincette.json.streams.Common.createTopologyContext;
import static net.pincette.json.streams.Common.transformFieldNames;
import static net.pincette.json.streams.Common.validateTopology;
import static net.pincette.mongo.BsonUtil.fromBson;
import static net.pincette.mongo.BsonUtil.toBsonDocument;
import static net.pincette.mongo.Expression.function;
import static net.pincette.mongo.Expression.replaceVariables;
import static net.pincette.mongo.JsonClient.aggregationPublisher;
import static net.pincette.mongo.JsonClient.find;
import static net.pincette.mongo.Match.predicate;
import static net.pincette.util.Builder.create;
import static net.pincette.util.Collections.map;
import static net.pincette.util.Collections.set;
import static net.pincette.util.Or.tryWith;
import static net.pincette.util.Pair.pair;
import static net.pincette.util.Util.tryToDoRethrow;
import static net.pincette.util.Util.tryToGetSilent;
import static org.apache.kafka.streams.kstream.Produced.valueSerde;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.reactivestreams.client.MongoCollection;
import java.io.File;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import net.pincette.function.SideEffect;
import net.pincette.jes.Aggregate;
import net.pincette.jes.EventToCommand;
import net.pincette.jes.GetDestinations;
import net.pincette.jes.Reactor;
import net.pincette.jes.util.JsonSerializer;
import net.pincette.jes.util.Reducer;
import net.pincette.jes.util.Streams;
import net.pincette.jes.util.Streams.Stop;
import net.pincette.jes.util.Streams.TopologyLifeCycle;
import net.pincette.jes.util.Streams.TopologyLifeCycleEmitter;
import net.pincette.json.Jslt.MapResolver;
import net.pincette.json.JsonUtil;
import net.pincette.mongo.BsonUtil;
import net.pincette.mongo.streams.Pipeline;
import net.pincette.rs.LambdaSubscriber;
import net.pincette.util.Pair;
import org.apache.kafka.common.serialization.Serdes.StringSerde;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.bson.Document;
import org.bson.conversions.Bson;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "run",
    description = "Runs topologies from a file containing a JSON array or a MongoDB collection.")
class Run implements Runnable {
  private static final String APPLICATION_ID = "application.id";
  private static final Duration DEFAULT_RESTART_BACKOFF = ofSeconds(10);
  private static final String EVENT = "$$event";
  private static final String KAFKA = "kafka";
  private static final String LOG_TOPIC = "logTopic";
  private static final String PROJECT = "$project";
  private static final String RESTART_BACKOFF = "restartBackoff";
  private static final Set<String> STREAM_TYPES = set(JOIN, MERGE, STREAM);
  private static final String TO_STRING = "toString";
  private static final String TO_TOPIC = "toTopic";
  private static final String TOPOLOGY_TOPIC = "topologyTopic";
  private static final String UNIQUE_EXPRESSION = "uniqueExpression";

  private final Context context;
  private final Duration restartBackoff;
  private final Map<String, TopologyEntry> running = new ConcurrentHashMap<>();
  @ArgGroup() FileOrCollection fileOrCollection;

  Run(final Context context) {
    this.context = context;
    restartBackoff = getRestartBackoff(context);
  }

  private static void addStreams(final Aggregate aggregate, final TopologyContext context) {
    final String type = aggregate.fullType();

    context.streams.put(type + "-aggregate", aggregate.aggregates());
    context.streams.put(type + "-command", aggregate.commands());
    context.streams.put(type + "-event", aggregate.events());
    context.streams.put(type + "-event-full", aggregate.eventsFull());
    context.streams.put(type + "-reply", aggregate.replies());
  }

  private static Pair<String, String> aggregateTypeParts(final JsonObject specification) {
    return Optional.of(specification.getString(AGGREGATE_TYPE).split("-"))
        .filter(parts -> parts.length == 2)
        .map(parts -> pair(parts[0], parts[1]))
        .orElse(null);
  }

  private static Map<String, String> convertJsltImports(final JsonObject jsltImports) {
    return jsltImports.entrySet().stream()
        .filter(e -> isString(e.getValue()))
        .collect(toMap(Entry::getKey, e -> asString(e.getValue()).getString()));
  }

  private static StreamsBuilder createAggregate(
      final JsonObject config, final TopologyContext context) {
    final Pair<String, String> aggregateType = aggregateTypeParts(config);
    final Aggregate aggregate =
        reducers(
            create(
                    () ->
                        new Aggregate()
                            .withApp(aggregateType.first)
                            .withType(aggregateType.second)
                            .withMongoDatabase(context.context.database)
                            .withBuilder(context.builder))
                .updateIf(() -> environment(config, context), Aggregate::withEnvironment)
                .updateIf(
                    () -> getValue(config, "/" + UNIQUE_EXPRESSION),
                    Aggregate::withUniqueExpression)
                .build(),
            config,
            context);

    aggregate.build();
    addStreams(aggregate, context);

    return context.builder;
  }

  private static KStream<String, JsonObject> createJoin(
      final JsonObject config, final TopologyContext context) {
    final Function<JsonObject, JsonValue> leftKey =
        function(config.getValue("/" + LEFT + "/" + ON), context.features);
    final Function<JsonObject, JsonValue> rightKey =
        function(config.getValue("/" + RIGHT + "/" + ON), context.features);

    return toStream(
        fromStream(config.getJsonObject(LEFT), context)
            .map((k, v) -> switchKey(v, leftKey))
            .filter((k, v) -> k != null && v != null)
            .join(
                fromStream(config.getJsonObject(RIGHT), context)
                    .map((k, v) -> switchKey(v, rightKey))
                    .filter((k, v) -> k != null && v != null),
                (v1, v2) -> createObjectBuilder().add(LEFT, v1).add(RIGHT, v2).build(),
                JoinWindows.of(Duration.ofMillis(config.getInt(WINDOW)))),
        config);
  }

  private static KStream<String, JsonObject> createMerge(
      final JsonObject config, final TopologyContext context) {
    return toStream(
        (config.containsKey(FROM_TOPICS)
                ? getStrings(config, FROM_TOPICS).map(topic -> stream(topic, context.builder))
                : getStrings(config, FROM_STREAMS).map(stream -> getStream(stream, context)))
            .reduce(KStream::merge)
            .orElse(null),
        config);
  }

  private static KStream<String, JsonObject> createPart(
      final JsonObject config, final TopologyContext context) {
    switch (config.getString(TYPE)) {
      case JOIN:
        return createJoin(config, context);
      case MERGE:
        return createMerge(config, context);
      case STREAM:
        return createStream(config, context);
      default:
        return null;
    }
  }

  private static StreamsBuilder createParts(final JsonArray parts, final TopologyContext context) {
    parts(parts, set(AGGREGATE)).forEach(part -> createAggregate(part, context));
    parts(parts, set(REACTOR)).forEach(part -> createReactor(part, context));
    parts(parts, STREAM_TYPES)
        .forEach(part -> context.configurations.put(part.getString(NAME), part));
    parts(parts, STREAM_TYPES).forEach(part -> getStream(part.getString(NAME), context));

    return context.builder;
  }

  private static StreamsBuilder createReactor(
      final JsonObject config, final TopologyContext context) {
    return create(
            () ->
                new Reactor()
                    .withSourceType(config.getString(SOURCE_TYPE))
                    .withDestinationType(config.getString(DESTINATION_TYPE))
                    .withBuilder(context.builder)
                    .withDestinations(
                        getDestinations(
                            config.getJsonArray(DESTINATIONS),
                            config.getString(DESTINATION_TYPE),
                            context.context))
                    .withEventToCommand(
                        eventToCommand(config.getString(EVENT_TO_COMMAND), context)))
        .updateIf(() -> environment(config, context), Reactor::withEnvironment)
        .updateIf(
            () -> ofNullable(config.getJsonObject(FILTER)), (r, f) -> r.withFilter(predicate(f)))
        .build()
        .build();
  }

  private static Reducer createReducer(
      final String jslt, final JsonObject validator, final TopologyContext context) {
    final Reducer reducer =
        reducer(tryTransformer(jslt, null, null, context.features.jsltResolver));

    return withResolver(
        validator != null
            ? compose(validator(context.validators.validator(validator)), reducer)
            : reducer,
        context.context.environment,
        context.context.database);
  }

  private static KStream<String, JsonObject> createStream(
      final JsonObject config, final TopologyContext context) {
    return toStream(
        Pipeline.create(
            context.application,
            fromStream(config, context),
            getPipeline(config),
            context.context.database,
            context.context.logLevel.equals(FINEST),
            context.features),
        config);
  }

  private static TopologyEntry createTopology(
      final JsonObject specification, final TopologyContext context) {
    final Properties streamsConfig = Streams.fromConfig(context.context.config, KAFKA);

    context.features =
        context.context.features.withJsltResolver(
            new MapResolver(
                ofNullable(specification.getJsonObject(JSLT_IMPORTS))
                    .map(Run::convertJsltImports)
                    .orElseGet(Collections::emptyMap)));

    final Logger logger = getLogger(specification, context.context);
    final Topology topology = createParts(specification.getJsonArray(PARTS), context).build();

    streamsConfig.setProperty(APPLICATION_ID, context.application);
    logger.log(INFO, "Topology:\n\n {0}", topology.describe());

    return new TopologyEntry(topology, streamsConfig, null, logger);
  }

  private static Optional<String> environment(
      final JsonObject config, final TopologyContext context) {
    return tryWith(() -> config.getString(ENVIRONMENT, null))
        .or(() -> context.context.environment)
        .get();
  }

  private static EventToCommand eventToCommand(final String jslt, final TopologyContext context) {
    final UnaryOperator<JsonObject> transformer =
        tryTransformer(jslt, null, null, context.features.jsltResolver);

    return event -> completedFuture(transformer.apply(event));
  }

  private static JsonObject fromMongoDB(final JsonObject json) {
    return transformFieldNames(json, Run::unescapeFieldName);
  }

  private static KStream<String, JsonObject> fromStream(
      final JsonObject config, final TopologyContext context) {
    return ofNullable(config.getString(FROM_TOPIC, null))
        .map(topic -> stream(topic, context.builder))
        .orElseGet(() -> getStream(config.getString(FROM_STREAM), context));
  }

  private static String getApplication(final TopologyEntry entry) {
    return entry.properties.getProperty(APPLICATION_ID);
  }

  private static Optional<String> getApplication(final ChangeStreamDocument<Document> change) {
    return ofNullable(change.getDocumentKey()).map(doc -> doc.getString(ID).getValue());
  }

  private static String getCollection(final String type, final Context context) {
    return type + (context.environment != null ? ("-" + context.environment) : "");
  }

  private static GetDestinations getDestinations(
      final JsonArray pipeline, final String destinationType, final Context context) {
    final JsonArray projected =
        from(concat(pipeline.stream(), Stream.of(o(f(PROJECT, o(f(ID, v(true))))))));

    return event ->
        aggregationPublisher(
            context.database.getCollection(getCollection(destinationType, context)),
            replaceVariables(projected, map(pair(EVENT, event))).asJsonArray());
  }

  private static Logger getLogger(final JsonObject specification, final Context context) {
    final String application = specification.getString(APPLICATION_FIELD);
    final Map<String, Object> kafkaConfig = fromConfig(context.config, KAFKA);
    final Logger logger = Logger.getLogger(application);

    logger.setLevel(context.logLevel);
    kafkaConfig.put(APPLICATION_ID, application);

    log(
        logger,
        specification.getString(VERSION, "unknown"),
        context.environment,
        createReliableProducer(kafkaConfig, new StringSerializer(), new JsonSerializer()),
        context.config.getString(LOG_TOPIC));

    return logger;
  }

  private static JsonArray getPipeline(final JsonObject config) {
    return getValue(config, "/" + PIPELINE)
        .map(JsonValue::asJsonArray)
        .orElseGet(JsonUtil::emptyArray);
  }

  private static Duration getRestartBackoff(final Context context) {
    return tryToGetSilent(() -> context.config.getDuration(RESTART_BACKOFF))
        .orElse(DEFAULT_RESTART_BACKOFF);
  }

  private static KStream<String, JsonObject> getStream(
      final String name, final TopologyContext context) {
    return context.streams.computeIfAbsent(
        name, k -> createPart(context.configurations.get(name), context));
  }

  private static Stream<JsonObject> getTopologies(
      final MongoCollection<Document> collection, final Bson filter) {
    return find(collection, filter).toCompletableFuture().join().stream().map(Run::fromMongoDB);
  }

  private static Aggregate reducers(
      final Aggregate aggregate, final JsonObject config, final TopologyContext context) {
    return getObjects(config, COMMANDS)
        .reduce(
            aggregate,
            (a, c) ->
                a.withReducer(
                    c.getString(NAME),
                    createReducer(c.getString(REDUCER), c.getJsonObject(VALIDATOR), context)),
            (a1, a2) -> a1);
  }

  private static Stream<JsonObject> parts(final JsonArray parts, final Set<String> types) {
    return parts.stream()
        .filter(JsonUtil::isObject)
        .map(JsonValue::asJsonObject)
        .filter(part -> types.contains(part.getString(TYPE)));
  }

  private static KStream<String, JsonObject> stream(
      final String topic, final StreamsBuilder builder) {
    return builder.stream(topic);
  }

  private static KeyValue<String, JsonObject> switchKey(
      final JsonObject json, final Function<JsonObject, JsonValue> key) {
    return ofNullable(toNative(key.apply(json)))
        .map(Object::toString)
        .map(k -> new KeyValue<>(k, json))
        .orElseGet(() -> new KeyValue<>(null, null));
  }

  @SuppressWarnings("java:S1905") // Fixes an ambiguity.
  private static KStream<String, JsonObject> toStream(
      final KStream<String, JsonObject> stream, final JsonObject config) {
    return ofNullable(config.getString(TO_TOPIC, null))
        .map(
            topic ->
                SideEffect.<KStream<String, JsonObject>>run(
                        () -> {
                          if (config.getBoolean(TO_STRING, false)) {
                            stream
                                .mapValues((ValueMapper<JsonObject, String>) JsonUtil::string)
                                .to(topic, valueSerde(new StringSerde()));
                          } else {
                            stream.to(topic);
                          }
                        })
                    .andThenGet(() -> null))
        .orElse(stream);
  }

  private static TopologyLifeCycleEmitter topologyLifeCycleEmitter(final Context context) {
    return tryToGetSilent(() -> context.config.getString(TOPOLOGY_TOPIC))
        .map(
            topic ->
                new TopologyLifeCycleEmitter(
                    topic,
                    createReliableProducer(
                        fromConfig(context.config, KAFKA),
                        new StringSerializer(),
                        new JsonSerializer())))
        .orElse(null);
  }

  private static String unescapeFieldName(final String name) {
    return name.replace(DOT, ".").replace(SLASH, "/").replace(DOLLAR, "$");
  }

  private void close() {
    running.values().forEach(v -> v.stop.stop());
  }

  private Optional<FileOrCollection.CollectionOptions> getCollectionOptions() {
    return ofNullable(fileOrCollection).map(f -> f.collection);
  }

  private Optional<File> getFile() {
    return ofNullable(fileOrCollection).map(f -> f.file).map(o -> o.file);
  }

  private Bson getFilter() {
    return tryWith(this::getFilterQuery).or(this::getFilterApplication).get().orElse(null);
  }

  private Bson getFilterApplication() {
    return getCollectionOptions()
        .map(o -> o.selection.application)
        .map(a -> eq(APPLICATION_FIELD, a))
        .orElse(null);
  }

  private Bson getFilterQuery() {
    return getCollectionOptions()
        .map(o -> o.selection.query)
        .flatMap(JsonUtil::from)
        .map(JsonValue::asJsonObject)
        .map(BsonUtil::fromJson)
        .orElse(null);
  }

  private Stream<JsonObject> getTopologies() {
    return getFile()
        .map(Common::readTopologies)
        .orElseGet(() -> getTopologies(getTopologyCollection(), getFilter()));
  }

  private MongoCollection<Document> getTopologyCollection() {
    return context.database.getCollection(
        Common.getTopologyCollection(
            getCollectionOptions().map(o -> o.collection).orElse(null), context));
  }

  private void handleChange(
      final ChangeStreamDocument<Document> change, final TopologyLifeCycle lifeCycle) {
    getApplication(change)
        .ifPresent(
            application -> {
              switch (change.getOperationType()) {
                case DELETE:
                  stop(application);
                  break;
                case INSERT:
                case REPLACE:
                case UPDATE:
                  start(change, lifeCycle);
                  break;
                default:
                  break;
              }
            });
  }

  public void run() {
    Optional.of(
            getTopologies()
                .map(
                    specification ->
                        pair(
                            specification,
                            createTopologyContext(
                                specification,
                                getFile()
                                    .map(file -> file.getAbsoluteFile().getParentFile())
                                    .orElse(null),
                                context)))
                .map(pair -> pair(build(pair.first, pair.second), pair.second))
                .filter(pair -> validateTopology(pair.first))
                .map(pair -> createTopology(pair.first, pair.second)))
        .map(this::start)
        .filter(result -> !result)
        .ifPresent(result -> exit(1));
  }

  private void restartError(final String application, final TopologyLifeCycle lifeCycle) {
    ofNullable(running.get(application))
        .ifPresent(
            entry -> {
              entry.logger.log(SEVERE, "Application {0} failed", getApplication(entry));
              entry.stop.stop();

              new Timer()
                  .schedule(
                      new TimerTask() {
                        public void run() {
                          restart(entry, lifeCycle);
                        }
                      },
                      restartBackoff.toMillis());
            });
  }

  private void restart(final TopologyEntry entry, final TopologyLifeCycle lifeCycle) {
    entry.logger.info("Restarting");
    running.put(getApplication(entry), start(entry, lifeCycle));
  }

  private boolean start(final Stream<TopologyEntry> topologies) {
    final TopologyLifeCycle lifeCycle = topologyLifeCycleEmitter(context);

    topologies
        .map(t -> pair(getApplication(t), start(t, lifeCycle)))
        .forEach(pair -> running.put(pair.first, pair.second));

    if (!getFile().isPresent()) {
      getTopologyCollection()
          .watch()
          .subscribe(new LambdaSubscriber<>(change -> handleChange(change, lifeCycle)));
    }

    getRuntime().addShutdownHook(new Thread(this::close));

    synchronized (this) {
      tryToDoRethrow(this::wait);
    }

    return true;
  }

  private TopologyEntry start(final TopologyEntry entry, final TopologyLifeCycle lifeCycle) {
    return new TopologyEntry(
        entry.topology,
        entry.properties,
        net.pincette.jes.util.Streams.start(
            entry.topology,
            entry.properties,
            lifeCycle,
            (stop, app) -> restartError(app, lifeCycle)),
        entry.logger);
  }

  private void start(
      final ChangeStreamDocument<Document> change, final TopologyLifeCycle lifeCycle) {
    ofNullable(change.getFullDocument())
        .map(doc -> fromBson(toBsonDocument(doc)))
        .ifPresent(
            specification ->
                restart(
                    createTopology(
                        specification, createTopologyContext(specification, null, context)),
                    lifeCycle));
  }

  private void stop(final String application) {
    ofNullable(running.remove(application))
        .ifPresent(
            entry -> {
              entry.logger.info("Stopping");
              entry.stop.stop();
            });
  }

  private static class FileOrCollection {
    @ArgGroup(exclusive = false)
    private FileOptions file;

    @ArgGroup(exclusive = false)
    private CollectionOptions collection;

    private static class FileOptions {
      @Option(
          names = {"-f", "--file"},
          required = true,
          description = "A JSON file containing an array of topologies.")
      private File file;
    }

    private static class CollectionOptions {
      @Option(
          names = {"-c", "--collection"},
          description = "A MongoDB collection containing the topologies.")
      private String collection;

      @ArgGroup private TopologySelection selection;

      private static class TopologySelection {
        @Option(
            names = {"-a", "--application"},
            description =
                "An application from the MongoDB collection containing the topologies. This is "
                    + "a shorthand for the query {\"application\": \"name\"}.")
        private String application;

        @Option(
            names = {"-q", "--query"},
            description = "The MongoDB query into the collection containing the topologies.")
        private String query;
      }
    }
  }

  private static class TopologyEntry {
    private final Logger logger;
    private final Properties properties;
    private final Stop stop;
    private final Topology topology;

    private TopologyEntry(
        final Topology topology,
        final Properties properties,
        final Stop stop,
        final Logger logger) {
      this.topology = topology;
      this.properties = properties;
      this.stop = stop;
      this.logger = logger;
    }
  }
}