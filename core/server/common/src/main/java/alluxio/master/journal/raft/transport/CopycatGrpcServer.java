/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.journal.raft.transport;

import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.grpc.GrpcServer;
import alluxio.grpc.GrpcServerBuilder;
import alluxio.grpc.GrpcService;
import alluxio.security.user.UserState;

import io.atomix.catalyst.concurrent.ThreadContext;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.Connection;
import io.atomix.catalyst.transport.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Copycat transport {@link Server} implementation that uses Alluxio gRPC.
 */
public class CopycatGrpcServer implements Server {
  private static final Logger LOG = LoggerFactory.getLogger(CopycatGrpcServer.class);

  /** Alluxio configuration. */
  private final AlluxioConfiguration mConf;
  /** Authentication user. */
  private final UserState mUserState;

  /** Underlying gRPC server. */
  private GrpcServer mGrpcServer;
  /** Bind address for underlying server. */
  private Address mActiveAddress;

  /** List of all connections created by this server. */
  private final List<Connection> mConnections;

  /** Whether this server is closed. */
  private boolean mClosed = false;

  /**
   * Creates copycat transport server that can be used to accept connections from remote copycat
   * clients.
   *
   * @param conf Alluxio configuration
   * @param userState authentication user
   */
  public CopycatGrpcServer(AlluxioConfiguration conf, UserState userState) {
    mConf = conf;
    mUserState = userState;
    mConnections = new LinkedList<>();
  }

  @Override
  public synchronized CompletableFuture<Void> listen(Address address,
      Consumer<Connection> listener) {
    LOG.debug("Copycat transport server binding to: {}", address);
    return ThreadContext.currentContextOrThrow().execute(() -> {
      // Listener that notifies both this server instance and given listener.
      Consumer<Connection> forkListener = (connection) -> {
        addNewConnection(connection);
        listener.accept(connection);
      };

      // Create gRPC server.
      mGrpcServer =
          GrpcServerBuilder.forAddress(address.host(), address.socketAddress(), mConf, mUserState)
              .addService(new GrpcService(new CopycatMessageServiceClientHandler(forkListener,
                  ThreadContext.currentContextOrThrow(),
                  mConf.getMs(PropertyKey.MASTER_EMBEDDED_JOURNAL_ELECTION_TIMEOUT))))
              .build();

      try {
        mGrpcServer.start();
        mActiveAddress = address;

        LOG.info("Successfully started gRPC server for copycat transport at: {}", address);
        return null;
      } catch (IOException e) {
        mGrpcServer = null;
        LOG.debug("Failed to create gRPC server for copycat transport at: {}.", address, e);
        throw new RuntimeException(e);
      }
    });
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    if (mClosed || mGrpcServer == null) {
      return CompletableFuture.completedFuture(null);
    }

    LOG.debug("Closing copycat transport server at: {}", mActiveAddress);
    // Close created connections.
    List<CompletableFuture<Void>> connectionCloseFutures = new ArrayList<>(mConnections.size());
    for (Connection connection : mConnections) {
      connectionCloseFutures.add(connection.close());
    }
    mConnections.clear();
    return CompletableFuture.allOf(connectionCloseFutures.toArray(new CompletableFuture[0]))
        .thenRun(() -> {
          // Shut down gRPC server once all connections are closed.
          mGrpcServer.shutdown();
          mGrpcServer = null;
        });
  }

  /**
   * Used to keep track of all connections created by this server instance.
   *
   * @param serverConnection new client connection
   */
  private synchronized void addNewConnection(Connection serverConnection) {
    mConnections.add(serverConnection);
  }
}
