/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.ballerinalang.cassandra;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.HostDistance;
import com.datastax.driver.core.PlainTextAuthProvider;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.ProtocolOptions;
import com.datastax.driver.core.ProtocolVersion;
import com.datastax.driver.core.QueryOptions;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;
import com.datastax.driver.core.exceptions.DriverInternalError;
import com.datastax.driver.core.policies.ConstantReconnectionPolicy;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.datastax.driver.core.policies.DefaultRetryPolicy;
import com.datastax.driver.core.policies.DowngradingConsistencyRetryPolicy;
import com.datastax.driver.core.policies.ExponentialReconnectionPolicy;
import com.datastax.driver.core.policies.FallthroughRetryPolicy;
import com.datastax.driver.core.policies.LatencyAwarePolicy;
import com.datastax.driver.core.policies.LoggingRetryPolicy;
import com.datastax.driver.core.policies.RoundRobinPolicy;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import org.ballerinalang.jvm.StringUtils;
import org.ballerinalang.jvm.types.BType;
import org.ballerinalang.jvm.values.MapValue;
import org.ballerinalang.jvm.values.api.BString;
import org.ballerinalang.jvm.values.api.BValue;
import org.ballerinalang.util.exceptions.BallerinaException;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code CassandraDataSource} util class for Cassandra connector initialization.
 *
 * @since 0.95.0
 */
public class CassandraDataSource implements BValue {

    private Cluster cluster;

    private Session session;

    public Cluster getCluster() {
        return cluster;
    }

    public Session getSession() {
        return session;
    }

    /**
     * Initializes the Cassandra cluster.
     *
     * @param host     Host(s) Cassandra instance(s) reside(s)
     * @param port     Port for the Cassandra instance(s)
     * @param username Username if authentication is enabled
     * @param password Password if authentication is enabled
     * @param options  BStruct containing available options for cluster connection initialization
     */
    public void init(String host, int port, String username, String password, MapValue options) {
        Cluster.Builder builder = Cluster.builder();
        builder.addContactPoints(host.split(",")).build();
        if (port != -1) {
            builder.withPort(port);
        }
        populateAuthenticationOptions(builder, username, password);
        if (options != null) {
            builder = this.populateOptions(builder, options);
        }
        this.cluster = builder.build();
        this.session = this.cluster.connect();
    }

    /**
     * Populates the builder with Cassandra cluster initialization options.
     *
     * @param builder Cluster Builder
     * @param options BStruct containing available options for cluster connection initialization
     * @return Populated Cluster Builder
     */
    private Cluster.Builder populateOptions(Cluster.Builder builder, MapValue options) {
        MapValue queryOptionsConfig = options.getMapValue(StringUtils.fromString(
                ConnectionParam.QUERY_OPTIONS.getKey()));
        if (queryOptionsConfig != null) {
            populateQueryOptions(builder, queryOptionsConfig);
        }
        MapValue poolingOptionsConfig = options.getMapValue(StringUtils.fromString(
                ConnectionParam.POOLING_OPTIONS.getKey()));
        if (poolingOptionsConfig != null) {
            populatePoolingOptions(builder, poolingOptionsConfig);
        }
        MapValue socketOptionsConfig = options.getMapValue(StringUtils.fromString(
                ConnectionParam.SOCKET_OPTIONS.getKey()));
        if (socketOptionsConfig != null) {
            populateSocketOptions(builder, socketOptionsConfig);
        }
        MapValue protocolOptionsConfig = options.getMapValue(StringUtils.fromString(
                ConnectionParam.PROTOCOL_OPTIONS.getKey()));
        if (protocolOptionsConfig != null) {
            populateProtocolOptions(builder, protocolOptionsConfig);
        }
        BString clusterName = options.getStringValue(StringUtils.fromString(
                ConnectionParam.CLUSTER_NAME.getKey()));
        if (!clusterName.toString().equals("")) {
            builder.withClusterName(String.valueOf(clusterName));
        }
        boolean jmxReportingDisabled = options.getBooleanValue(StringUtils.fromString(
                ConnectionParam.WITHOUT_JMX_REPORTING.getKey()));
        if (jmxReportingDisabled) {
            builder.withoutJMXReporting();
        }
        boolean metricsDisabled = options.getBooleanValue(StringUtils.fromString(
                ConnectionParam.WITHOUT_METRICS.getKey()));
        if (metricsDisabled) {
            builder.withoutMetrics();
        }
        populateLoadBalancingPolicy(builder, options);
        populateReconnectionPolicy(builder, options);
        populateRetryPolicy(builder, options);

        return builder;
    }

    /**
     * Populates the Cassnadra RetryPolicy options in the Cluster Builder.
     *
     * @param builder Cluster Builder
     * @param options BStruct containing available options for cluster connection initialization
     */
    private void populateRetryPolicy(Cluster.Builder builder, MapValue options) {
        BString retryPolicyString = options.getStringValue(StringUtils.fromString(
                ConnectionParam.RETRY_POLICY.getKey()));
        if (!retryPolicyString.toString().equals("")) {
            RetryPolicy retryPolicy = retrieveRetryPolicy(retryPolicyString.toString());
            switch (retryPolicy) {
                case DEFAULT_RETRY_POLICY:
                    builder.withRetryPolicy(DefaultRetryPolicy.INSTANCE);
                    break;
                case FALLTHROUGH_RETRY_POLICY:
                    builder.withRetryPolicy(FallthroughRetryPolicy.INSTANCE);
                    break;
                case DOWNGRADING_CONSISTENCY_RETRY_POLICY:
                    builder.withRetryPolicy(DowngradingConsistencyRetryPolicy.INSTANCE);
                    break;
                case LOGGING_DEFAULT_RETRY_POLICY:
                    builder.withRetryPolicy(new LoggingRetryPolicy(DefaultRetryPolicy.INSTANCE));
                    break;
                case LOGGING_FALLTHROUGH_RETRY_POLICY:
                    builder.withRetryPolicy(new LoggingRetryPolicy(FallthroughRetryPolicy.INSTANCE));
                    break;
                case LOGGING_DOWNGRADING_CONSISTENCY_RETRY_POLICY:
                    builder.withRetryPolicy(new LoggingRetryPolicy(DowngradingConsistencyRetryPolicy.INSTANCE));
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "Support for the retry policy \"" + retryPolicy + "\" is not implemented yet");
            }
        }
    }

    private RetryPolicy retrieveRetryPolicy(String retryPolicy) {
        try {
            return RetryPolicy.fromPolicyName(retryPolicy);
        } catch (IllegalArgumentException e) {
            throw new BallerinaException("\"" + retryPolicy + "\" is not a valid retry policy");
        }
    }

    /**
     * Populates Reconnection Policy options in the Cluster Builder.
     *
     * @param builder Cluster Builder
     * @param options BStruct containing available options for cluster connection initialization
     */
    private void populateReconnectionPolicy(Cluster.Builder builder, MapValue options) {
        BString reconnectionPolicyString = options.getStringValue(StringUtils.fromString(
                ConnectionParam.RECONNECTION_POLICY.getKey()));

        if (!reconnectionPolicyString.toString().equals("")) {
            ReconnectionPolicy reconnectionPolicy = retrieveReconnectionPolicy(reconnectionPolicyString.toString());
            switch (reconnectionPolicy) {
                case CONSTANT_RECONNECTION_POLICY:
                    long constantReconnectionPolicyDelay = options
                            .getIntValue(StringUtils.fromString(
                                    ConnectionParam.CONSTANT_RECONNECTION_POLICY_DELAY.getKey()));
                    if (constantReconnectionPolicyDelay != -1) {
                        builder.withReconnectionPolicy(new ConstantReconnectionPolicy(constantReconnectionPolicyDelay));
                    } else {
                        throw new BallerinaException("constantReconnectionPolicyDelay required for the "
                                                             +
                                                             "initialization of ConstantReconnectionPolicy, has not " +
                                                             "been set");
                    }
                    break;
                case EXPONENTIAL_RECONNECTION_POLICY:
                    long exponentialReconnectionPolicyBaseDelay = options
                            .getIntValue(StringUtils.fromString(
                                    ConnectionParam.EXPONENTIAL_RECONNECTION_POLICY_BASE_DELAY.getKey()));
                    long exponentialReconnectionPolicyMaxDelay = options
                            .getIntValue(StringUtils.fromString(
                                    ConnectionParam.EXPONENTIAL_RECONNECTION_POLICY_MAX_DELAY.getKey()));
                    if (exponentialReconnectionPolicyBaseDelay != -1 && exponentialReconnectionPolicyMaxDelay != -1) {
                        builder.withReconnectionPolicy(
                                new ExponentialReconnectionPolicy(exponentialReconnectionPolicyBaseDelay,
                                                                  exponentialReconnectionPolicyMaxDelay));
                    } else {
                        throw new BallerinaException("exponentialReconnectionPolicyBaseDelay or "
                                                             + "exponentialReconnectionPolicyMaxDelay required for the "
                                                             +
                                                             "initialization of ConstantReconnectionPolicy, has not " +
                                                             "been set");
                    }
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "Support for the reconnection policy \"" + reconnectionPolicy +
                                    "\" is not implemented yet");
            }
        }

    }

    private ReconnectionPolicy retrieveReconnectionPolicy(String reconnectionPolicy) {
        try {
            return ReconnectionPolicy.fromPolicyName(reconnectionPolicy);
        } catch (IllegalArgumentException e) {
            throw new BallerinaException("\"" + reconnectionPolicy + "\"" + " is not a valid reconnection policy");
        }
    }

    /**
     * Populates LoadBalancing Policy options in the Cluster Builder.
     *
     * @param builder Cluster Builder
     * @param options BStruct containing available options for cluster connection initialization
     */
    private void populateLoadBalancingPolicy(Cluster.Builder builder, MapValue options) {
        BString dataCenter = options.getStringValue(StringUtils.fromString(ConnectionParam.DATA_CENTER.getKey()));
        BString loadBalancingPolicyString = options.getStringValue(StringUtils.fromString(
                ConnectionParam.LOAD_BALANCING_POLICY.getKey()));
        boolean allowRemoteDCsForLocalConsistencyLevel =
                options.getBooleanValue(StringUtils.fromString(
                        ConnectionParam.ALLOW_REMOTE_DCS_FOR_LOCAL_CONSISTENCY_LEVEL.getKey()));

        if (!loadBalancingPolicyString.toString().equals("")) {
            LoadBalancingPolicy loadBalancingPolicy = retrieveLoadBalancingPolicy(loadBalancingPolicyString.toString());
            switch (loadBalancingPolicy) {
                case DC_AWARE_ROUND_ROBIN_POLICY:
                    if (dataCenter != null && !dataCenter.toString().equals("")) {
                        if (allowRemoteDCsForLocalConsistencyLevel) {
                            builder.withLoadBalancingPolicy(DCAwareRoundRobinPolicy.builder().withLocalDc(
                                    dataCenter.toString()).allowRemoteDCsForLocalConsistencyLevel().build());
                        } else {
                            builder.withLoadBalancingPolicy(
                                    DCAwareRoundRobinPolicy.builder().withLocalDc(dataCenter.toString()).build());
                        }
                    } else {
                        if (allowRemoteDCsForLocalConsistencyLevel) {
                            builder.withLoadBalancingPolicy(
                                    (DCAwareRoundRobinPolicy.builder().allowRemoteDCsForLocalConsistencyLevel()
                                            .build()));
                        } else {
                            builder.withLoadBalancingPolicy((DCAwareRoundRobinPolicy.builder().build()));
                        }
                    }
                    break;
                case LATENCY_AWARE_ROUND_ROBIN_POLICY:
                    builder.withLoadBalancingPolicy(LatencyAwarePolicy.builder(new RoundRobinPolicy()).build());
                    break;
                case ROUND_ROBIN_POLICY:
                    builder.withLoadBalancingPolicy(new RoundRobinPolicy());
                    break;
                case TOKEN_AWARE_ROUND_ROBIN_POLICY:
                    builder.withLoadBalancingPolicy(new TokenAwarePolicy(new RoundRobinPolicy()));
                    break;
                default:
                    throw new UnsupportedOperationException(
                            "Support for the load balancing policy \"" + loadBalancingPolicy +
                                    "\" is not implemented yet");
            }
        }
    }

    private LoadBalancingPolicy retrieveLoadBalancingPolicy(String loadBalancingPolicy) {
        try {
            return LoadBalancingPolicy.fromPolicyName(loadBalancingPolicy);
        } catch (IllegalArgumentException e) {
            throw new BallerinaException("\"" + loadBalancingPolicy + "\"" + " is not a valid load balancing policy");
        }
    }

    /**
     * Populates Authentication Options in the Cluster Builder.
     *
     * @param builder  Cluster Builder
     * @param username Provided Username
     * @param password Provided Password
     */
    private void populateAuthenticationOptions(Cluster.Builder builder, String username, String password) {
        // TODO: Enable functionality for custom authenticators
        builder.withAuthProvider(new PlainTextAuthProvider(username, password));
    }

    /**
     * Populates Query Options in the Cluster Builder.
     *
     * @param builder            Cluster Builder
     * @param queryOptionsConfig Config containing available query options for cluster connection initialization
     */
    private void populateQueryOptions(Cluster.Builder builder, MapValue queryOptionsConfig) {
        QueryOptions queryOptions = new QueryOptions();

        BString consistencyLevel = queryOptionsConfig.getStringValue(StringUtils.fromString(
                QueryOptionsParam.CONSISTENCY_LEVEL.getKey()));
        BString serialConsistencyLevel = queryOptionsConfig
                .getStringValue(StringUtils.fromString(QueryOptionsParam.SERIAL_CONSISTENCY_LEVEL.getKey()));
        boolean defaultIdempotence =
                queryOptionsConfig.getBooleanValue(StringUtils.fromString(
                        QueryOptionsParam.DEFAULT_IDEMPOTENCE.getKey()));
        boolean metadataEnabled =
                queryOptionsConfig.getBooleanValue(StringUtils.fromString(QueryOptionsParam.METADATA_ENABLED.getKey()));
        boolean reprepareOnUp = queryOptionsConfig.getBooleanValue(StringUtils.fromString(
                QueryOptionsParam.REPREPARE_ON_UP.getKey()));
        queryOptions.setReprepareOnUp(reprepareOnUp);
        boolean prepareOnAllHosts =
                queryOptionsConfig.getBooleanValue(StringUtils.fromString(
                        QueryOptionsParam.PREPARE_ON_ALL_HOSTS.getKey()));
        queryOptions.setPrepareOnAllHosts(prepareOnAllHosts);
        int fetchSize = Math.toIntExact(queryOptionsConfig.getIntValue(StringUtils.fromString(
                QueryOptionsParam.FETCH_SIZE.getKey())));
        int maxPendingRefreshNodeListRequests = Math.toIntExact(queryOptionsConfig
                .getIntValue(StringUtils.fromString(QueryOptionsParam.MAX_PENDING_REFRESH_NODELIST_REQUESTS.getKey())));
        int maxPendingRefreshNodeRequests = (Math.toIntExact(queryOptionsConfig
                .getIntValue(StringUtils.fromString(QueryOptionsParam.MAX_PENDING_REFRESH_NODE_REQUESTS.getKey()))));
        int maxPendingRefreshSchemaRequests = Math.toIntExact(queryOptionsConfig
                .getIntValue(StringUtils.fromString(QueryOptionsParam.MAX_PENDING_REFRESH_SCHEMA_REQUESTS.getKey())));
        int refreshNodeListIntervalMillis = Math.toIntExact(queryOptionsConfig
                .getIntValue(StringUtils.fromString(QueryOptionsParam.REFRESH_NODELIST_INTERVAL_MILLIS.getKey())));
        int refreshNodeIntervalMillis = Math.toIntExact(queryOptionsConfig
                .getIntValue(StringUtils.fromString(QueryOptionsParam.REFRESH_NODE_INTERNAL_MILLIS.getKey())));
        int refreshSchemaIntervalMillis = Math.toIntExact(queryOptionsConfig
                .getIntValue(StringUtils.fromString(QueryOptionsParam.REFRESH_SCHEMA_INTERVAL_MILLIS.getKey())));

        if (!consistencyLevel.toString().equals("")) {
            queryOptions.setConsistencyLevel(retrieveConsistencyLevel(consistencyLevel.toString()));
        }
        if (!serialConsistencyLevel.toString().equals("")) {
            queryOptions.setSerialConsistencyLevel(retrieveSerialConsistencyLevel(serialConsistencyLevel.toString()));
        }
        queryOptions.setDefaultIdempotence(defaultIdempotence);
        queryOptions.setMetadataEnabled(metadataEnabled);
        if (fetchSize != -1) {
            queryOptions.setFetchSize(fetchSize);
        }
        if (maxPendingRefreshNodeListRequests != -1) {
            queryOptions.setMaxPendingRefreshNodeListRequests(maxPendingRefreshNodeListRequests);
        }
        if (maxPendingRefreshNodeRequests != -1) {
            queryOptions.setMaxPendingRefreshNodeRequests(maxPendingRefreshNodeRequests);
        }
        if (maxPendingRefreshSchemaRequests != -1) {
            queryOptions.setMaxPendingRefreshSchemaRequests(maxPendingRefreshSchemaRequests);
        }
        if (refreshNodeListIntervalMillis != -1) {
            queryOptions.setRefreshNodeIntervalMillis(refreshNodeListIntervalMillis);
        }
        if (refreshNodeIntervalMillis != -1) {
            queryOptions.setRefreshNodeIntervalMillis(refreshNodeIntervalMillis);
        }
        if (refreshSchemaIntervalMillis != -1) {
            queryOptions.setRefreshSchemaIntervalMillis(refreshSchemaIntervalMillis);
        }
        builder.withQueryOptions(queryOptions);
    }

    /**
     * Populates Pooling Options in the Cluster Builder.
     *
     * @param builder              Cluster Builder
     * @param poolingOptionsConfig Config containing available pooling options for cluster connection initialization
     */
    private void populatePoolingOptions(Cluster.Builder builder, MapValue poolingOptionsConfig) {
        PoolingOptions poolingOptions = new PoolingOptions();

        int coreConnectionsPerHostLocal = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.CORE_CONNECTIONS_PER_HOST_LOCAL.getKey())));
        int maxConnectionsPerHostLocal = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.MAX_CONNECTIONS_PER_HOST_LOCAL.getKey())));
        int newConnectionThresholdLocal = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.NEW_CONNECTION_THRESHOLD_LOCAL.getKey())));
        int coreConnectionsPerHostRemote = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.CORE_CONNECTIONS_PER_HOST_REMOTE.getKey())));
        int maxConnectionsPerHostRemote = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.MAX_CONNECTIONS_PER_HOST_REMOTE.getKey())));
        int newConnectionThresholdRemote = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.NEW_CONNECTION_THRESHOLD_REMOTE.getKey())));
        int maxRequestsPerConnectionLocal = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.MAX_REQUESTS_PER_CONNECTION_LOCAL.getKey())));
        int maxRequestsPerConnectionRemote = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.MAX_REQUESTS_PER_CONNECTION_REMOTE.getKey())));
        int idleTimeoutSeconds = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.IDLE_TIMEOUT_SECONDS.getKey())));
        int poolTimeoutMillis = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.POOL_TIMEOUT_MILLIS.getKey())));
        int maxQueueSize =
                Math.toIntExact(poolingOptionsConfig.getIntValue(StringUtils.fromString(
                        PoolingOptionsParam.MAX_QUEUE_SIZE.getKey())));
        int heartbeatIntervalSeconds = Math.toIntExact(poolingOptionsConfig
                .getIntValue(StringUtils.fromString(PoolingOptionsParam.HEART_BEAT_INTERVAL_SECONDS.getKey())));

        if (coreConnectionsPerHostLocal != -1) {
            poolingOptions.setCoreConnectionsPerHost(HostDistance.LOCAL, coreConnectionsPerHostLocal);
        }
        if (coreConnectionsPerHostRemote != -1) {
            poolingOptions.setCoreConnectionsPerHost(HostDistance.REMOTE, coreConnectionsPerHostRemote);
        }
        if (maxConnectionsPerHostLocal != -1) {
            poolingOptions.setMaxConnectionsPerHost(HostDistance.LOCAL, maxConnectionsPerHostLocal);
        }
        if (maxConnectionsPerHostRemote != -1) {
            poolingOptions.setMaxConnectionsPerHost(HostDistance.REMOTE, maxConnectionsPerHostRemote);
        }
        if (newConnectionThresholdLocal != -1) {
            poolingOptions.setNewConnectionThreshold(HostDistance.LOCAL, newConnectionThresholdLocal);
        }
        if (newConnectionThresholdRemote != -1) {
            poolingOptions.setNewConnectionThreshold(HostDistance.REMOTE, newConnectionThresholdRemote);
        }
        if (maxRequestsPerConnectionLocal != -1) {
            poolingOptions.setMaxRequestsPerConnection(HostDistance.LOCAL, maxRequestsPerConnectionLocal);
        }
        if (maxRequestsPerConnectionRemote != -1) {
            poolingOptions.setMaxRequestsPerConnection(HostDistance.REMOTE, maxRequestsPerConnectionRemote);
        }
        if (idleTimeoutSeconds != -1) {
            poolingOptions.setIdleTimeoutSeconds(idleTimeoutSeconds);
        }
        if (poolTimeoutMillis != -1) {
            poolingOptions.setPoolTimeoutMillis(poolTimeoutMillis);
        }
        if (maxQueueSize != -1) {
            poolingOptions.setMaxQueueSize(maxQueueSize);
        }
        if (heartbeatIntervalSeconds != -1) {
            poolingOptions.setHeartbeatIntervalSeconds(heartbeatIntervalSeconds);
        }

        builder.withPoolingOptions(poolingOptions);
    }

    /**
     * Populates Socket Options in the Cluster Builder.
     *
     * @param builder             Cluster Builder
     * @param socketOptionsConfig BStruct containing available socket options for cluster connection initialization
     */
    private void populateSocketOptions(Cluster.Builder builder, MapValue socketOptionsConfig) {
        SocketOptions socketOptions = new SocketOptions();

        int connectTimeoutMillis = Math.toIntExact(socketOptionsConfig
                .getIntValue(StringUtils.fromString(SocketOptionsParam.CONNECT_TIMEOUT_MILLIS.getKey())));
        int readTimeoutMillis = Math.toIntExact(socketOptionsConfig
                .getIntValue(StringUtils.fromString(SocketOptionsParam.READ_TIMEOUT_MILLIS.getKey())));
        int soLinger = Math.toIntExact(socketOptionsConfig.getIntValue(StringUtils.fromString(
                SocketOptionsParam.SO_LINGER.getKey())));
        int receiveBufferSize =
                Math.toIntExact(socketOptionsConfig.getIntValue(StringUtils.fromString(
                        SocketOptionsParam.RECEIVE_BUFFER_SIZE.getKey())));
        int sendBufferSize =
                Math.toIntExact(socketOptionsConfig.getIntValue(StringUtils.fromString(
                        SocketOptionsParam.SEND_BUFFER_SIZE.getKey())));

        if (connectTimeoutMillis != -1) {
            socketOptions.setConnectTimeoutMillis(connectTimeoutMillis);
        }
        if (readTimeoutMillis != -1) {
            socketOptions.setReadTimeoutMillis(readTimeoutMillis);
        }
        if (soLinger != -1) {
            socketOptions.setSoLinger(soLinger);
        }
        if (receiveBufferSize != -1) {
            socketOptions.setReceiveBufferSize(receiveBufferSize);
        }
        if (sendBufferSize != -1) {
            socketOptions.setSendBufferSize(sendBufferSize);
        }

    /* TODO: Driver does not set these by default. It takes the default values of the underlying netty transport.
        But if we take the inputs as booleans and if the value set in the bal file is "false", we wouldn't know
        if it was set by the user or not. So need to decide how to handle this.
        boolean keepAlive = socketOptionsConfig.getBooleanField(SocketOptionsParam.KEEP_ALIVE.getKey()) != 0;
        boolean reuseAddress = socketOptionsConfig.getBooleanField(SocketOptionsParam.REUSE_ADDRESS.getKey()) != 0;
        boolean tcpNoDelay = socketOptionsConfig.getBooleanField(SocketOptionsParam.TCP_NO_DELAY.getKey()) != 0;

        socketOptions.setKeepAlive(keepAlive);
        socketOptions.setReuseAddress(reuseAddress);
        socketOptions.setTcpNoDelay(tcpNoDelay);
    */
        builder.withSocketOptions(socketOptions);
    }

    /**
     * Populates Protocol Options in the Cluster Builder.
     *
     * @param builder               Cluster Builder
     * @param protocolOptionsConfig BStruct containing available protocol options for cluster connection initialization
     */
    private void populateProtocolOptions(Cluster.Builder builder, MapValue protocolOptionsConfig) {
        boolean sslEnabled = protocolOptionsConfig.getBooleanValue(StringUtils.fromString(
                ProtocolOptionsParam.SSL_ENABLED.getKey()));
        boolean noCompact = protocolOptionsConfig.getBooleanValue(StringUtils.fromString(
                ProtocolOptionsParam.NO_COMPACT.getKey()));

        int maxSchemaAgreementWaitSeconds = Math.toIntExact(protocolOptionsConfig
                .getIntValue(StringUtils.fromString(ProtocolOptionsParam.MAX_SCHEMA_AGREEMENT_WAIT_SECONDS.getKey())));
        BString compression = protocolOptionsConfig.getStringValue(StringUtils.fromString(
                ProtocolOptionsParam.COMPRESSION.getKey()));
        BString initialProtocolVersion = protocolOptionsConfig
                .getStringValue(StringUtils.fromString(ProtocolOptionsParam.INITIAL_PROTOCOL_VERSION.getKey()));

        if (sslEnabled) {
            builder = builder.withSSL();
        }
        if (noCompact) {
            builder.withNoCompact();
        }
        if (maxSchemaAgreementWaitSeconds != -1) {
            builder.withMaxSchemaAgreementWaitSeconds(maxSchemaAgreementWaitSeconds);
        }
        if (!compression.toString().equals("")) {
            builder.withCompression(retrieveCompression(compression.toString()));
        }
        if (!initialProtocolVersion.toString().equals("")) {
            builder.withProtocolVersion(retrieveProtocolVersion(initialProtocolVersion.toString()));
        }
    }

    @Override
    public String stringValue() {
        return null;
    }

    @Override
    public BType getType() {
        return null;
    }

    private ProtocolVersion retrieveProtocolVersion(String protocolVersion) {
        try {
            return ProtocolVersion.valueOf(protocolVersion);
        } catch (IllegalArgumentException e) {
            throw new BallerinaException("\"" + protocolVersion + "\" is not a valid protocol version");
        }
    }

    private ProtocolOptions.Compression retrieveCompression(String compression) {
        try {
            return ProtocolOptions.Compression.valueOf(compression);
        } catch (IllegalArgumentException e) {
            throw new BallerinaException("\"" + compression + "\" is not a valid compression type");
        }
    }

    private ConsistencyLevel retrieveSerialConsistencyLevel(String consistencyLevel) {
        try {
            ConsistencyLevel serialConsistencyLevel = ConsistencyLevel.valueOf(consistencyLevel);
            if (serialConsistencyLevel.isSerial()) {
                return serialConsistencyLevel;
            } else {
                throw new BallerinaException("\"" + consistencyLevel + "\" is not a valid serial consistency level");
            }
        } catch (DriverInternalError e) {
            throw new BallerinaException("\"" + consistencyLevel + "\" is not a valid serial consistency level");
        }
    }

    private ConsistencyLevel retrieveConsistencyLevel(String consistencyLevel) {
        try {
            return ConsistencyLevel.valueOf(consistencyLevel);
        } catch (DriverInternalError e) {
            throw new BallerinaException("\"" + consistencyLevel + "\" is not a valid consistency level");
        }
    }

    private enum SocketOptionsParam {
        // int params
        CONNECT_TIMEOUT_MILLIS("connectTimeoutMillis"), READ_TIMEOUT_MILLIS("readTimeoutMillis"), SO_LINGER(
                "soLinger"), RECEIVE_BUFFER_SIZE("receiveBufferSize"), SEND_BUFFER_SIZE("sendBufferSize");

        private String key;

        SocketOptionsParam(String key) {
            this.key = key;
        }

        private String getKey() {
            return key;
        }
    }

    private enum PoolingOptionsParam {
        // int params
        MAX_REQUESTS_PER_CONNECTION_LOCAL("maxRequestsPerConnectionLocal"), MAX_REQUESTS_PER_CONNECTION_REMOTE(
                "maxRequestsPerConnectionRemote"), IDLE_TIMEOUT_SECONDS("idleTimeoutSeconds"), POOL_TIMEOUT_MILLIS(
                "poolTimeoutMillis"), MAX_QUEUE_SIZE("maxQueueSize"), HEART_BEAT_INTERVAL_SECONDS(
                "heartbeatIntervalSeconds"), CORE_CONNECTIONS_PER_HOST_LOCAL(
                "coreConnectionsPerHostLocal"), MAX_CONNECTIONS_PER_HOST_LOCAL(
                "maxConnectionsPerHostLocal"), NEW_CONNECTION_THRESHOLD_LOCAL(
                "newConnectionThresholdLocal"), CORE_CONNECTIONS_PER_HOST_REMOTE(
                "coreConnectionsPerHostRemote"), MAX_CONNECTIONS_PER_HOST_REMOTE(
                "maxConnectionsPerHostRemote"), NEW_CONNECTION_THRESHOLD_REMOTE("newConnectionThresholdRemote");

        private String key;

        PoolingOptionsParam(String index) {
            this.key = index;
        }

        private String getKey() {
            return key;
        }
    }

    private enum QueryOptionsParam {
        // string params
        CONSISTENCY_LEVEL("consistencyLevel"), SERIAL_CONSISTENCY_LEVEL("serialConsistencyLevel"),

        // boolean params
        DEFAULT_IDEMPOTENCE("defaultIdempotence"), METADATA_ENABLED("metadataEnabled"), REPREPARE_ON_UP(
                "reprepareOnUp"), PREPARE_ON_ALL_HOSTS("prepareOnAllHosts"),

        // int params
        FETCH_SIZE("fetchSize"), MAX_PENDING_REFRESH_NODELIST_REQUESTS(
                "maxPendingRefreshNodeListRequests"), MAX_PENDING_REFRESH_NODE_REQUESTS(
                "maxPendingRefreshNodeRequests"), MAX_PENDING_REFRESH_SCHEMA_REQUESTS(
                "maxPendingRefreshSchemaRequests"), REFRESH_NODELIST_INTERVAL_MILLIS(
                "refreshNodeListIntervalMillis"), REFRESH_NODE_INTERNAL_MILLIS(
                "refreshNodeIntervalMillis"), REFRESH_SCHEMA_INTERVAL_MILLIS("refreshSchemaIntervalMillis");

        private String key;

        QueryOptionsParam(String key) {
            this.key = key;
        }

        private String getKey() {
            return key;
        }
    }

    private enum ProtocolOptionsParam {
        // boolean params
        SSL_ENABLED("sslEnabled"), NO_COMPACT("noCompact"),

        // int params
        MAX_SCHEMA_AGREEMENT_WAIT_SECONDS("maxSchemaAgreementWaitSeconds"),

        // string params
        INITIAL_PROTOCOL_VERSION("initialProtocolVersion"), COMPRESSION("compression");

        private String key;

        ProtocolOptionsParam(String key) {
            this.key = key;
        }

        private String getKey() {
            return key;
        }
    }

    private enum ConnectionParam {
        // string params
        CLUSTER_NAME("clusterName"), LOAD_BALANCING_POLICY("loadBalancingPolicy"), RECONNECTION_POLICY(
                "reconnectionPolicy"), RETRY_POLICY("retryPolicy"), DATA_CENTER("dataCenter"),

        // boolean params
        WITHOUT_METRICS("withoutMetrics"), WITHOUT_JMX_REPORTING(
                "withoutJMXReporting"), ALLOW_REMOTE_DCS_FOR_LOCAL_CONSISTENCY_LEVEL(
                "allowRemoteDCsForLocalConsistencyLevel"),

        // int params
        CONSTANT_RECONNECTION_POLICY_DELAY(
                "constantReconnectionPolicyDelay"), EXPONENTIAL_RECONNECTION_POLICY_BASE_DELAY(
                "exponentialReconnectionPolicyBaseDelay"), EXPONENTIAL_RECONNECTION_POLICY_MAX_DELAY(
                "exponentialReconnectionPolicyMaxDelay"),

        // ref params
        QUERY_OPTIONS("queryOptionsConfig"), POOLING_OPTIONS("poolingOptionsConfig"), SOCKET_OPTIONS(
                "socketOptionsConfig"), PROTOCOL_OPTIONS("protocolOptionsConfig");

        private String key;

        ConnectionParam(String key) {
            this.key = key;
        }

        private String getKey() {
            return key;
        }

    }

    private enum LoadBalancingPolicy {
        DC_AWARE_ROUND_ROBIN_POLICY("DCAwareRoundRobinPolicy"), LATENCY_AWARE_ROUND_ROBIN_POLICY(
                "LatencyAwarePolicy"), ROUND_ROBIN_POLICY("RoundRobinPolicy"), TOKEN_AWARE_ROUND_ROBIN_POLICY(
                "TokenAwarePolicy");
        // TODO: Add support for HostFilterPolicy.
        // TODO: Add support for ErrorAwarePolicy which is still(datastax driver 3.4.0) in beta mode

        private String loadBalancingPolicy;

        private static final Map<String, LoadBalancingPolicy> policyMap = new HashMap<>();

        LoadBalancingPolicy(String loadBalancingPolicy) {
            this.loadBalancingPolicy = loadBalancingPolicy;
        }

        static {
            LoadBalancingPolicy[] policies = values();
            for (LoadBalancingPolicy policy : policies) {
                policyMap.put(policy.getPolicyName(), policy);
            }
        }

        public static LoadBalancingPolicy fromPolicyName(String policyName) {
            LoadBalancingPolicy policy = policyMap.get(policyName);
            if (policy == null) {
                throw new IllegalArgumentException("Unsupported Load Balancing policy: " + policyName);
            } else {
                return policy;
            }
        }

        private String getPolicyName() {
            return this.loadBalancingPolicy;
        }

    }

    private enum ReconnectionPolicy {
        CONSTANT_RECONNECTION_POLICY("ConstantReconnectionPolicy"), EXPONENTIAL_RECONNECTION_POLICY(
                "ExponentialReconnectionPolicy");

        private String reconnectionPolicy;

        private static final Map<String, ReconnectionPolicy> policyMap = new HashMap<>();

        ReconnectionPolicy(String reconnectionPolicy) {
            this.reconnectionPolicy = reconnectionPolicy;
        }

        static {
            ReconnectionPolicy[] policies = values();
            for (ReconnectionPolicy policy : policies) {
                policyMap.put(policy.getPolicyName(), policy);
            }
        }

        public static ReconnectionPolicy fromPolicyName(String policyName) {
            ReconnectionPolicy policy = policyMap.get(policyName);
            if (policy == null) {
                throw new IllegalArgumentException("Unsupported Reconnection policy: " + policyName);
            } else {
                return policy;
            }
        }

        private String getPolicyName() {
            return this.reconnectionPolicy;
        }

    }

    private enum RetryPolicy {
        DEFAULT_RETRY_POLICY("DefaultRetryPolicy"), DOWNGRADING_CONSISTENCY_RETRY_POLICY(
                "DowngradingConsistencyRetryPolicy"), FALLTHROUGH_RETRY_POLICY(
                "FallthroughRetryPolicy"), LOGGING_DEFAULT_RETRY_POLICY(
                "LoggingDefaultRetryPolicy"), LOGGING_DOWNGRADING_CONSISTENCY_RETRY_POLICY(
                "LoggingDowngradingConsistencyRetryPolicy"), LOGGING_FALLTHROUGH_RETRY_POLICY(
                "LoggingFallthroughRetryPolicy");

        private String retryPolicy;

        private static final Map<String, RetryPolicy> policyMap = new HashMap<>();

        RetryPolicy(String retryPolicy) {
            this.retryPolicy = retryPolicy;
        }

        private String getPolicyName() {
            return this.retryPolicy;
        }

        static {
            RetryPolicy[] policies = values();
            for (RetryPolicy policy : policies) {
                policyMap.put(policy.getPolicyName(), policy);
            }
        }

        public static RetryPolicy fromPolicyName(String policyName) {
            RetryPolicy mechanism = policyMap.get(policyName);
            if (mechanism == null) {
                throw new IllegalArgumentException("Unsupported Retry policy: " + policyName);
            } else {
                return mechanism;
            }
        }
    }
}
