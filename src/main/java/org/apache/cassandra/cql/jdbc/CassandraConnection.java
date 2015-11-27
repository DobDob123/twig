/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */
package org.apache.cassandra.cql.jdbc;

import com.datastax.driver.core.*;
import org.apache.cassandra.thrift.*;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.commons.lang3.StringUtils;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.sql.*;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;

import static org.apache.cassandra.cql.jdbc.CassandraResultSet.DEFAULT_CONCURRENCY;
import static org.apache.cassandra.cql.jdbc.CassandraResultSet.DEFAULT_HOLDABILITY;
import static org.apache.cassandra.cql.jdbc.CassandraResultSet.DEFAULT_TYPE;
import static org.apache.cassandra.cql.jdbc.Utils.*;


/**
 * Implementation class for {@link java.sql.Connection}.
 */
class CassandraConnection extends AbstractConnection implements Connection
{

    private static final Logger logger = LoggerFactory.getLogger(CassandraConnection.class);
    public static Integer roundRobinIndex;
    static final String IS_VALID_CQLQUERY_2_0_0 = "SELECT COUNT(1) FROM system.Versions WHERE component = 'cql';";
    static final String IS_VALID_CQLQUERY_3_0_0 = "SELECT COUNT(1) FROM system.\"Versions\" WHERE component = 'cql';";
    
    public static final int DB_MAJOR_VERSION = 1;
    public static final int DB_MINOR_VERSION = 2;
    public static final String DB_PRODUCT_NAME = "Cassandra";
    public static final String DEFAULT_CQL_VERSION = "3.0.0";

    public static Compression defaultCompression = Compression.GZIP;

    private final boolean autoCommit = true;

    private final int transactionIsolation = Connection.TRANSACTION_NONE;

    /**
     * Connection Properties
     */
    private Properties connectionProps;

    /**
     * Client Info Properties (currently unused)
     */
    private Properties clientInfo = new Properties();

    /**
     * Set of all Statements that have been created by this connection
     */
    private Set<Statement> statements = new ConcurrentSkipListSet<Statement>();

    /**
     * Cassandra 3.x Session
     */
    private Session session;

    // TODO: DEPRECATE
    //private Cassandra.Client client;
    //private TTransport transport;

    protected long timeOfLastFailure = 0;
    protected int numFailures = 0;
    protected String username = null;
    protected String url = null;
    protected String cluster;//current catalog
    protected String currentKeyspace;//current schema
    protected TreeSet<String> hostListPrimary;
    protected TreeSet<String> hostListBackup;
    int majorCqlVersion;

    //private TSocket socket;
    
    PreparedStatement isAlive = null;
    
    private String currentCqlVersion;
    
    ConsistencyLevel defaultConsistencyLevel;

    /**
     * Instantiates a new CassandraConnection.
     */
    public CassandraConnection(Properties props) throws SQLException
    {

        hostListPrimary = new TreeSet<String>();
    	hostListBackup = new TreeSet<String>();
        connectionProps = (Properties)props.clone();
        clientInfo = new Properties();

        url = PROTOCOL + createSubName(props);

        String[] hosts = {};
        String host = props.getProperty(TAG_SERVER_NAME);
        int port = Integer.parseInt(props.getProperty(TAG_PORT_NUMBER));

        currentKeyspace = props.getProperty(TAG_DATABASE_NAME);
        username = props.getProperty(TAG_USER);
        String password = props.getProperty(TAG_PASSWORD);
        String version = props.getProperty(TAG_CQL_VERSION,DEFAULT_CQL_VERSION);

        connectionProps.setProperty(TAG_ACTIVE_CQL_VERSION, version);
        majorCqlVersion = getMajor(version);
        defaultConsistencyLevel = ConsistencyLevel.valueOf(props.getProperty(TAG_CONSISTENCY_LEVEL, ConsistencyLevel.ONE.name()));

        // dealing with multiple hosts passed as seeds in the JDBC URL : jdbc:cassandra://lyn4e900.tlt--lyn4e901.tlt--lyn4e902.tlt:9160/fluks
        // in this phase we get the list of all the nodes of the cluster
        String currentHost = "";

        try {

            if(host.contains("--")){
                hosts = new String[host.split("--").length];
                int i=0;
                for(String h:host.split("--")){
                    hosts[i]=h;
                    i++;
                }
            }else{
                hosts = new String[1];
                hosts[0] = host;
            }

            Random rand = new Random();

            currentHost = hosts[rand.nextInt( hosts.length)];
            logger.debug("Chosen seed : " + currentHost);

            Cluster.Builder connectionBuilder = Cluster.builder().addContactPoint(host);

            if (port > 0) {
                connectionBuilder.withPort(port);
            }

            if (!StringUtils.isEmpty(username)) {
                connectionBuilder.withCredentials(username, password);
            }

            // configure SSL if specified
            SSLOptions sslOptions = createSSLOptions(props);

            if (sslOptions != null) {
                connectionBuilder.withSSL(sslOptions);
            }

            Cluster cluster = connectionBuilder.build();
            session = cluster.connect();

            // if keyspace was specified - use it
            if (!session.isClosed() && !StringUtils.isEmpty(currentKeyspace)) {
                session.execute(String.format("USE %s;", currentKeyspace));
            }

        } catch (Exception e){
            String msg = String.format("Connection Fails to %s: %s", currentHost, e.toString());
            logger.error(msg, e);
            throw new SQLException(msg, e);
        }

    }

    private static SSLOptions createSSLOptions(Properties properties)
    {
        String storePath = properties.getProperty(TAG_TRUST_STORE);
        String storePass = properties.getProperty(TAG_TRUST_PASSWORD);

        if ((storePath != null) && (storePass != null))
        {
            SSLContext context;
            try
            {
                context = getSSLContext(storePath, storePass);

                return new SSLOptions(context, SSLOptions.DEFAULT_SSL_CIPHER_SUITES);

            }
            catch (UnrecoverableKeyException xk) {
                logger.error("Key Fails for Trust Store: " + storePath, xk);
            } catch (KeyManagementException mx) {
                logger.error("Key Management Error for Trust Store: " + storePath, mx);
            } catch (NoSuchAlgorithmException nx) {
                logger.error("Algorithm Not Found for Trust Store: " + storePath, nx);
            } catch (KeyStoreException kx) {
                logger.error("Key Store Error for Trust Store: " + storePath, kx);
            } catch (CertificateException cx) {
                logger.error("Certificate Error for Trust Store: " + storePath, cx);
            } catch (IOException ix) {
                logger.error("Unable to Read Store Trust Store: " + storePath, ix);
            }

        }

        return null;

    }

    private static SSLContext getSSLContext(String trustPath, String trustPass)
            throws NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException, UnrecoverableKeyException, KeyManagementException {

        FileInputStream tsf = null;
        SSLContext ctx = null;

        try {

            tsf = new FileInputStream(trustPath);
            ctx = SSLContext.getInstance("SSL");

            KeyStore ts = KeyStore.getInstance("JKS");
            ts.load(tsf, trustPass.toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);

            ctx.init(null, tmf.getTrustManagers(), new SecureRandom());

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (tsf != null) {
                try {
                    tsf.close();
                } catch (IOException ix) {
                    logger.warn("Error Closing Trust Store: " + trustPath, ix);
                }
            }

        }

        return ctx;

    }

    /**
     * Get the Major portion of a string like : Major.minor.patch where 2 is the default
     */
    private int getMajor(String version)
    {
        int major = 0;
        String[] parts = version.split("\\.");
        try
        {
            major = Integer.valueOf(parts[0]);
        }
        catch (Exception e)
        {
            major = 2;
        }
        return major;
    }
    
    private final void checkNotClosed() throws SQLException
    {
        if (isClosed()) throw new SQLNonTransientConnectionException(WAS_CLOSED_CON);
    }

    public void clearWarnings() throws SQLException
    {
        // This implementation does not support the collection of warnings so clearing is a no-op
        // but it is still an exception to call this on a closed connection.
        checkNotClosed();
    }

    /**
     * On close of connection.
     */
    public synchronized void close() throws SQLException
    {
        // close all statements associated with this connection upon close
        for (Statement statement : statements)
            statement.close();
        statements.clear();
        
        if (isConnected())
        {
            // then disconnect from the transport                
            disconnect();
        }
    }

    public void commit() throws SQLException
    {
        checkNotClosed();
        throw new SQLFeatureNotSupportedException(ALWAYS_AUTOCOMMIT);
    }

    public Statement createStatement() throws SQLException
    {
        checkNotClosed();
        Statement statement = new CassandraStatement(this);
        statements.add(statement);
        return statement;
    }

    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException
    {
        checkNotClosed();
        Statement statement = new CassandraStatement(this, null, resultSetType, resultSetConcurrency);
        statements.add(statement);
        return statement;
    }

    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException
    {
        checkNotClosed();
        Statement statement = new CassandraStatement(this, null, resultSetType, resultSetConcurrency, resultSetHoldability);
        statements.add(statement);
        return statement;
    }

    public boolean getAutoCommit() throws SQLException
    {
        checkNotClosed();
        return autoCommit;
    }

    public Properties getConnectionProps()
    {
        return connectionProps;
    }

    public String getCatalog() throws SQLException
    {
        checkNotClosed();
        return cluster;
    }

    public void setSchema(String schema) throws SQLException
    {
        checkNotClosed();
        currentKeyspace = schema;
    }

    public String getSchema() throws SQLException
    {
        checkNotClosed();
        return currentKeyspace;
    }

    public Properties getClientInfo() throws SQLException
    {
        checkNotClosed();
        return clientInfo;
    }

    public String getClientInfo(String label) throws SQLException
    {
        checkNotClosed();
        return clientInfo.getProperty(label);
    }

    public int getHoldability() throws SQLException
    {
        checkNotClosed();
        // the rationale is there are really no commits in Cassandra so no boundary...
        return DEFAULT_HOLDABILITY;
    }

    public DatabaseMetaData getMetaData() throws SQLException
    {
        checkNotClosed();
        return new CassandraDatabaseMetaData(this);
    }

    public int getTransactionIsolation() throws SQLException
    {
        checkNotClosed();
        return transactionIsolation;
    }

    public SQLWarning getWarnings() throws SQLException
    {
        checkNotClosed();
        // the rationale is there are no warnings to return in this implementation...
        return null;
    }

    public synchronized boolean isClosed() throws SQLException
    {

        return !isConnected();
    }

    public boolean isReadOnly() throws SQLException
    {
        checkNotClosed();
        return false;
    }

    /**
     * Returns true if the connection has not been closed and is still valid.
     * @param timeout  Time in seconds to wait for the database verification operation to complete.
     * @return Result is {@code true} if connection is alive or {@code false} if it tries
     *         to verify and times out.
     * @throws SQLTimeoutException
     */
    @Override
    public boolean isValid(int timeout) throws SQLTimeoutException
    {
        return ((session != null) && !session.isClosed());
    }

    public boolean isWrapperFor(Class<?> arg0) throws SQLException
    {
        return false;
    }

    public String nativeSQL(String sql) throws SQLException
    {
        checkNotClosed();
        // the rationale is there are no distinction between grammars in this implementation...
        // so we are just return the input argument
        return sql;
    }

    public CassandraPreparedStatement prepareStatement(String cql) throws SQLException
    {
        return prepareStatement(cql,DEFAULT_TYPE,DEFAULT_CONCURRENCY,DEFAULT_HOLDABILITY);
    }

    public CassandraPreparedStatement prepareStatement(String cql, int rsType) throws SQLException
    {
        return prepareStatement(cql,rsType,DEFAULT_CONCURRENCY,DEFAULT_HOLDABILITY);
    }

    public CassandraPreparedStatement prepareStatement(String cql, int rsType, int rsConcurrency) throws SQLException
    {
        return prepareStatement(cql,rsType,rsConcurrency,DEFAULT_HOLDABILITY);
    }

    public CassandraPreparedStatement prepareStatement(String cql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        checkNotClosed();
        CassandraPreparedStatement statement = new CassandraPreparedStatement(this, cql, rsType,rsConcurrency,rsHoldability);
        statements.add(statement);
        return statement;
    }

    public void rollback() throws SQLException
    {
        checkNotClosed();
        throw new SQLFeatureNotSupportedException(ALWAYS_AUTOCOMMIT);
    }

    public void setAutoCommit(boolean autoCommit) throws SQLException
    {
        checkNotClosed();
        if (!autoCommit) throw new SQLFeatureNotSupportedException(ALWAYS_AUTOCOMMIT);
    }

    public void setCatalog(String arg0) throws SQLException
    {
        checkNotClosed();
        // the rationale is there are no catalog name to set in this implementation...
        // so we are "silently ignoring" the request
    }

    public void setClientInfo(Properties props) throws SQLClientInfoException
    {
        // we don't use them but we will happily collect them for now...
        if (props != null) clientInfo = props;
    }

    public void setClientInfo(String key, String value) throws SQLClientInfoException
    {
        // we don't use them but we will happily collect them for now...
        clientInfo.setProperty(key, value);
    }

    public void setHoldability(int arg0) throws SQLException
    {
        checkNotClosed();
        // the rationale is there are no holdability to set in this implementation...
        // so we are "silently ignoring" the request
    }

    public void setReadOnly(boolean arg0) throws SQLException
    {
        checkNotClosed();
        // the rationale is all connections are read/write in the Cassandra implementation...
        // so we are "silently ignoring" the request
    }

    public void setTransactionIsolation(int level) throws SQLException
    {
        checkNotClosed();
        if (level != Connection.TRANSACTION_NONE) throw new SQLFeatureNotSupportedException(NO_TRANSACTIONS);
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        throw new SQLFeatureNotSupportedException(String.format(NO_INTERFACE, iface.getSimpleName()));
    }

    /**
     * Execute a CQL query.
     *
     * @param queryStr    a CQL query string
     * @param compression query compression to use
     * @param consistencyLevel    the CQL query consistency level
     * @return the query results encoded as a CqlResult structure
     * @throws InvalidRequestException     on poorly constructed or illegal requests
     * @throws UnavailableException        when not all required replicas could be created/read
     * @throws TimedOutException           when a cluster operation timed out
     * @throws SchemaDisagreementException when the client side and server side are at different versions of schema (Thrift)
     * @throws TException                  when there is a error in Thrift processing
     */
    protected com.datastax.driver.core.ResultSet execute(String queryStr, Compression compression, ConsistencyLevel consistencyLevel)
    {
        //currentKeyspace = determineCurrentKeyspace(queryStr, currentKeyspace);
        return session.execute(queryStr);
    }

    /**
     * Execute a CQL query using the default compression methodology.
     *
     * @param queryStr a CQL query string
     * @param consistencyLevel	the CQL query consistency level
     * @return the query results encoded as a CqlResult structure
     * @throws InvalidRequestException     on poorly constructed or illegal requests
     * @throws UnavailableException        when not all required replicas could be created/read
     * @throws TimedOutException           when a cluster operation timed out
     * @throws SchemaDisagreementException when the client side and server side are at different versions of schema (Thrift)
     * @throws TException                  when there is a error in Thrift processing
     */
    protected com.datastax.driver.core.ResultSet execute(String queryStr, ConsistencyLevel consistencyLevel)
    {
        return execute(queryStr, defaultCompression, consistencyLevel);
    }

//    protected com.datastax.driver.core.ResultSet execute(int itemId, List<ByteBuffer> values, ConsistencyLevel consistencyLevel)
//    {
//        try
//        {
//            if (majorCqlVersion==3) return client.execute_prepared_cql3_query(itemId, values, consistencyLevel);
//            else                    return client.execute_prepared_cql_query(itemId, values);
//        }
//        catch (TException error)
//        {
//            numFailures++;
//            timeOfLastFailure = System.currentTimeMillis();
//            throw error;
//        }
//    }
    
    protected com.datastax.driver.core.PreparedStatement prepare(String queryStr, Compression compression)
    {
        return session.prepare(queryStr);
    }
    
    protected com.datastax.driver.core.PreparedStatement prepare(String queryStr)
    {
        return prepare(queryStr, defaultCompression);
    }
    
    /**
     * Remove a Statement from the Open Statements List
     */
    protected boolean removeStatement(Statement statement)
    {
        return statements.remove(statement);
    }
    
    /**
     * Shutdown the remote connection
     */
    protected void disconnect()
    {
        session.close();
    }

    /**
     * Connection state.
     */
    protected boolean isConnected()
    {
        return ((session != null) && !session.isClosed());
    }

    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("CassandraConnection [connectionProps=");
        builder.append(connectionProps);
        builder.append("]");
        return builder.toString();
    }

    protected final com.datastax.driver.core.ResultSet execute(BoundStatement boundStatement) {
        return session.execute(boundStatement);
    }
}
