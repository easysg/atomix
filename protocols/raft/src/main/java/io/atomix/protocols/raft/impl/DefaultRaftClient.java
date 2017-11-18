/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft.impl;

import io.atomix.cluster.NodeId;
import io.atomix.primitive.PrimitiveType;
import io.atomix.primitive.proxy.PrimitiveProxy;
import io.atomix.primitive.proxy.impl.BlockingAwarePrimitiveProxy;
import io.atomix.primitive.proxy.impl.DelegatingPrimitiveProxy;
import io.atomix.primitive.proxy.impl.RecoveringPrimitiveProxy;
import io.atomix.primitive.proxy.impl.RetryingPrimitiveProxy;
import io.atomix.protocols.raft.RaftClient;
import io.atomix.protocols.raft.RaftMetadataClient;
import io.atomix.protocols.raft.protocol.RaftClientProtocol;
import io.atomix.protocols.raft.proxy.RaftProxy;
import io.atomix.protocols.raft.proxy.RecoveryStrategy;
import io.atomix.protocols.raft.proxy.impl.DefaultRaftProxy;
import io.atomix.protocols.raft.proxy.impl.MemberSelectorManager;
import io.atomix.protocols.raft.proxy.impl.RaftProxyManager;
import io.atomix.utils.concurrent.ThreadContext;
import io.atomix.utils.concurrent.ThreadContextFactory;
import io.atomix.utils.logging.ContextualLoggerFactory;
import io.atomix.utils.logging.LoggerContext;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default Raft client implementation.
 */
public class DefaultRaftClient implements RaftClient {
  private final String clientId;
  private final Collection<NodeId> cluster;
  private final RaftClientProtocol protocol;
  private final ThreadContextFactory threadContextFactory;
  private final ThreadContext threadContext;
  private final RaftMetadataClient metadata;
  private final MemberSelectorManager selectorManager = new MemberSelectorManager();
  private final RaftProxyManager sessionManager;

  public DefaultRaftClient(
      String clientId,
      NodeId nodeId,
      Collection<NodeId> cluster,
      RaftClientProtocol protocol,
      ThreadContextFactory threadContextFactory) {
    this.clientId = checkNotNull(clientId, "clientId cannot be null");
    this.cluster = checkNotNull(cluster, "cluster cannot be null");
    this.protocol = checkNotNull(protocol, "protocol cannot be null");
    this.threadContextFactory = checkNotNull(threadContextFactory, "threadContextFactory cannot be null");
    this.threadContext = threadContextFactory.createContext();
    this.metadata = new DefaultRaftMetadataClient(clientId, protocol, selectorManager, threadContextFactory.createContext());
    this.sessionManager = new RaftProxyManager(clientId, nodeId, protocol, selectorManager, threadContextFactory);
  }

  @Override
  public String clientId() {
    return clientId;
  }

  @Override
  public RaftMetadataClient metadata() {
    return metadata;
  }

  @Override
  public synchronized CompletableFuture<RaftClient> connect(Collection<NodeId> cluster) {
    CompletableFuture<RaftClient> future = new CompletableFuture<>();

    // If the provided cluster list is null or empty, use the default list.
    if (cluster == null || cluster.isEmpty()) {
      cluster = this.cluster;
    }

    // If the default list is null or empty, use the default host:port.
    if (cluster == null || cluster.isEmpty()) {
      throw new IllegalArgumentException("No cluster specified");
    }

    // Reset the connection list to allow the selection strategy to prioritize connections.
    sessionManager.resetConnections(null, cluster);

    // Register the session manager.
    sessionManager.open().whenCompleteAsync((result, error) -> {
      if (error == null) {
        future.complete(this);
      } else {
        future.completeExceptionally(error);
      }
    }, threadContext);
    return future;
  }

  @Override
  public RaftProxy.Builder proxyBuilder(String primitiveName, PrimitiveType primitiveType) {
    return new ProxyBuilder(primitiveName, primitiveType);
  }

  @Override
  public CompletableFuture<Set<String>> getPrimitives(PrimitiveType primitiveType) {
    return metadata.getSessions(primitiveType).thenApply(result -> result.stream()
        .map(m -> m.primitiveName())
        .collect(Collectors.toSet()));
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    return sessionManager.close().thenRunAsync(threadContextFactory::close);
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("id", clientId)
        .toString();
  }

  /**
   * Default Raft session builder.
   */
  private class ProxyBuilder extends RaftProxy.Builder {
    ProxyBuilder(String name, PrimitiveType primitiveType) {
      super(name, primitiveType);
    }

    @Override
    public PrimitiveProxy build() {
      // Create a proxy builder that uses the session manager to open a session.
      RaftProxy.Builder proxyBuilder = new RaftProxy.Builder(name, primitiveType) {
        @Override
        public RaftProxy build() {
          return new DefaultRaftProxy(
              name,
              primitiveType,
              protocol,
              selectorManager,
              sessionManager,
              readConsistency,
              communicationStrategy,
              threadContextFactory.createContext(),
              minTimeout,
              maxTimeout);
        }
      };

      // Populate the proxy client builder.
      proxyBuilder
          .withReadConsistency(readConsistency)
          .withMaxRetries(maxRetries)
          .withRetryDelay(retryDelay)
          .withCommunicationStrategy(communicationStrategy)
          .withRecoveryStrategy(recoveryStrategy)
          .withMinTimeout(minTimeout)
          .withMaxTimeout(maxTimeout);

      PrimitiveProxy proxy;

      // If the recovery strategy is set to RECOVER, wrap the builder in a recovering proxy client.
      if (recoveryStrategy == RecoveryStrategy.RECOVER) {
        proxy = new RecoveringPrimitiveProxy(clientId, name, primitiveType, proxyBuilder, threadContextFactory.createContext());
      } else {
        proxy = proxyBuilder.build();
      }

      // If max retries is set, wrap the client in a retrying proxy client.
      if (maxRetries > 0) {
        proxy = new RetryingPrimitiveProxy(proxy, threadContextFactory.createContext(), maxRetries, retryDelay);
      }

      // Default the executor to use the configured thread pool executor and create a blocking aware proxy client.
      Executor executor = this.executor != null ? this.executor : threadContextFactory.createContext();
      proxy = new BlockingAwarePrimitiveProxy(proxy, executor);

      // Create the proxy.
      return new DelegatingPrimitiveProxy(proxy);
    }
  }

  /**
   * Default Raft client builder.
   */
  public static class Builder extends RaftClient.Builder {
    public Builder(Collection<NodeId> cluster) {
      super(cluster);
    }

    @Override
    public RaftClient build() {
      checkNotNull(nodeId, "nodeId cannot be null");
      Logger log = ContextualLoggerFactory.getLogger(DefaultRaftClient.class, LoggerContext.builder(RaftClient.class)
          .addValue(clientId)
          .build());
      ThreadContextFactory threadContextFactory = threadModel.factory("raft-client-" + clientId + "-%d", threadPoolSize, log);
      return new DefaultRaftClient(clientId, nodeId, cluster, protocol, threadContextFactory);
    }
  }
}
