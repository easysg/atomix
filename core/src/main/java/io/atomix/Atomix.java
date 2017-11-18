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
package io.atomix;

import com.google.common.collect.Sets;
import io.atomix.cluster.ClusterMetadata;
import io.atomix.cluster.ClusterService;
import io.atomix.cluster.ManagedClusterService;
import io.atomix.cluster.Node;
import io.atomix.cluster.NodeId;
import io.atomix.cluster.impl.DefaultClusterService;
import io.atomix.cluster.messaging.ClusterCommunicationService;
import io.atomix.cluster.messaging.ClusterEventService;
import io.atomix.cluster.messaging.ManagedClusterCommunicationService;
import io.atomix.cluster.messaging.ManagedClusterEventService;
import io.atomix.cluster.messaging.impl.DefaultClusterCommunicationService;
import io.atomix.cluster.messaging.impl.DefaultClusterEventService;
import io.atomix.messaging.ManagedMessagingService;
import io.atomix.messaging.MessagingService;
import io.atomix.messaging.impl.NettyMessagingService;
import io.atomix.primitive.AsyncPrimitive;
import io.atomix.primitive.DistributedPrimitiveBuilder;
import io.atomix.primitive.PrimitiveType;
import io.atomix.primitive.PrimitiveTypeRegistry;
import io.atomix.primitive.SyncPrimitive;
import io.atomix.primitive.partition.ManagedPartition;
import io.atomix.primitive.partition.ManagedPartitionService;
import io.atomix.primitive.partition.PartitionId;
import io.atomix.primitive.partition.PartitionMetadata;
import io.atomix.primitive.partition.PartitionService;
import io.atomix.primitive.partition.impl.DefaultPartitionService;
import io.atomix.primitives.PrimitivesService;
import io.atomix.primitives.impl.PartitionedPrimitivesService;
import io.atomix.protocols.raft.partition.impl.RaftPartition;
import io.atomix.rest.ManagedRestService;
import io.atomix.rest.impl.VertxRestService;
import io.atomix.utils.Managed;
import io.atomix.utils.concurrent.SingleThreadContext;
import io.atomix.utils.concurrent.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Atomix!
 */
public class Atomix implements PrimitivesService, Managed<Atomix> {

  /**
   * Returns a new Atomix builder.
   *
   * @return a new Atomix builder
   */
  public static Builder builder() {
    return new Builder();
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(Atomix.class);

  private final ManagedClusterService cluster;
  private final ManagedMessagingService messagingService;
  private final ManagedClusterCommunicationService clusterCommunicator;
  private final ManagedClusterEventService clusterEventService;
  private final ManagedPartitionService partitions;
  private final ManagedRestService restService;
  private final PrimitivesService primitives;
  private final AtomicBoolean open = new AtomicBoolean();
  private final ThreadContext context = new SingleThreadContext("atomix-%d");

  protected Atomix(
      ManagedClusterService cluster,
      ManagedMessagingService messagingService,
      ManagedClusterCommunicationService clusterCommunicator,
      ManagedClusterEventService clusterEventService,
      ManagedPartitionService partitions,
      ManagedRestService restService,
      PrimitivesService primitives) {
    this.cluster = checkNotNull(cluster, "cluster cannot be null");
    this.messagingService = checkNotNull(messagingService, "messagingService cannot be null");
    this.clusterCommunicator = checkNotNull(clusterCommunicator, "clusterCommunicator cannot be null");
    this.clusterEventService = checkNotNull(clusterEventService, "clusterEventService cannot be null");
    this.partitions = checkNotNull(partitions, "partitions cannot be null");
    this.restService = restService; // ManagedRestService can be null
    this.primitives = checkNotNull(primitives, "primitives cannot be null");
  }

  /**
   * Returns the Atomix cluster.
   *
   * @return the Atomix cluster
   */
  public ClusterService getClusterService() {
    return cluster;
  }

  /**
   * Returns the cluster communicator.
   *
   * @return the cluster communicator
   */
  public ClusterCommunicationService getCommunicationService() {
    return clusterCommunicator;
  }

  /**
   * Returns the cluster event service.
   *
   * @return the cluster event service
   */
  public ClusterEventService getEventService() {
    return clusterEventService;
  }

  /**
   * Returns the cluster messenger.
   *
   * @return the cluster messenger
   */
  public MessagingService getMessagingService() {
    return messagingService;
  }

  /**
   * Returns the partition service.
   *
   * @return the partition service
   */
  public PartitionService getPartitionService() {
    return partitions;
  }

  /**
   * Returns the primitive service.
   *
   * @return the primitive service
   */
  public PrimitivesService getPrimitiveService() {
    return primitives;
  }

  @Override
  public <B extends DistributedPrimitiveBuilder<B, S, A>, S extends SyncPrimitive, A extends AsyncPrimitive> B primitiveBuilder(
      String name, PrimitiveType<B, S, A> primitiveType) {
    return primitives.primitiveBuilder(name, primitiveType);
  }

  @Override
  public Set<String> getPrimitiveNames(PrimitiveType primitiveType) {
    return primitives.getPrimitiveNames(primitiveType);
  }

  @Override
  public CompletableFuture<Atomix> open() {
    return messagingService.open()
        .thenComposeAsync(v -> cluster.open(), context)
        .thenComposeAsync(v -> clusterCommunicator.open(), context)
        .thenComposeAsync(v -> clusterEventService.open(), context)
        .thenComposeAsync(v -> partitions.open(), context)
        .thenComposeAsync(v -> restService != null ? restService.open() : CompletableFuture.completedFuture(null), context)
        .thenApplyAsync(v -> {
          open.set(true);
          LOGGER.info("Started");
          return this;
        }, context);
  }

  @Override
  public boolean isOpen() {
    return open.get();
  }

  @Override
  public CompletableFuture<Void> close() {
    return restService.close()
        .thenComposeAsync(v -> partitions.close(), context)
        .thenComposeAsync(v -> clusterCommunicator.close(), context)
        .thenComposeAsync(v -> clusterEventService.close(), context)
        .thenComposeAsync(v -> cluster.close(), context)
        .thenComposeAsync(v -> messagingService.close(), context)
        .thenRunAsync(() -> {
          context.close();
          open.set(false);
          LOGGER.info("Stopped");
        });
  }

  @Override
  public boolean isClosed() {
    return !open.get();
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("partitions", getPartitionService())
        .toString();
  }

  /**
   * Atomix builder.
   */
  public static class Builder implements io.atomix.utils.Builder<Atomix> {
    private static final String DEFAULT_CLUSTER_NAME = "atomix";
    private static final int DEFAULT_NUM_BUCKETS = 128;
    private String name = DEFAULT_CLUSTER_NAME;
    private int httpPort;
    private Node localNode;
    private Collection<Node> bootstrapNodes;
    private int numPartitions;
    private int partitionSize;
    private Collection<PartitionMetadata> partitions;
    private PrimitiveTypeRegistry primitiveTypes = new PrimitiveTypeRegistry();
    private File dataDir = new File(System.getProperty("user.dir"), "data");

    /**
     * Sets the cluster name.
     *
     * @param name the cluster name
     * @return the cluster metadata builder
     * @throws NullPointerException if the name is null
     */
    public Builder withClusterName(String name) {
      this.name = checkNotNull(name, "name cannot be null");
      return this;
    }

    /**
     * Sets the HTTP port.
     *
     * @param httpPort the HTTP port
     * @return the Atomix builder
     */
    public Builder withHttpPort(int httpPort) {
      this.httpPort = httpPort;
      return this;
    }

    /**
     * Sets the local node metadata.
     *
     * @param localNode the local node metadata
     * @return the cluster metadata builder
     */
    public Builder withLocalNode(Node localNode) {
      this.localNode = checkNotNull(localNode, "localNode cannot be null");
      return this;
    }

    /**
     * Sets the bootstrap nodes.
     *
     * @param bootstrapNodes the nodes from which to bootstrap the cluster
     * @return the cluster metadata builder
     * @throws NullPointerException if the bootstrap nodes are {@code null}
     */
    public Builder withBootstrapNodes(Node... bootstrapNodes) {
      return withBootstrapNodes(Arrays.asList(checkNotNull(bootstrapNodes)));
    }

    /**
     * Sets the bootstrap nodes.
     *
     * @param bootstrapNodes the nodes from which to bootstrap the cluster
     * @return the cluster metadata builder
     * @throws NullPointerException if the bootstrap nodes are {@code null}
     */
    public Builder withBootstrapNodes(Collection<Node> bootstrapNodes) {
      this.bootstrapNodes = checkNotNull(bootstrapNodes, "bootstrapNodes cannot be null");
      return this;
    }

    /**
     * Sets the number of partitions.
     *
     * @param numPartitions the number of partitions
     * @return the cluster metadata builder
     * @throws IllegalArgumentException if the number of partitions is not positive
     */
    public Builder withNumPartitions(int numPartitions) {
      checkArgument(numPartitions > 0, "numPartitions must be positive");
      this.numPartitions = numPartitions;
      return this;
    }

    /**
     * Sets the partition size.
     *
     * @param partitionSize the partition size
     * @return the cluster metadata builder
     * @throws IllegalArgumentException if the partition size is not positive
     */
    public Builder withPartitionSize(int partitionSize) {
      checkArgument(partitionSize > 0, "partitionSize must be positive");
      this.partitionSize = partitionSize;
      return this;
    }

    /**
     * Sets the partitions.
     *
     * @param partitions the partitions
     * @return the cluster metadata builder
     */
    public Builder withPartitions(Collection<PartitionMetadata> partitions) {
      this.partitions = checkNotNull(partitions, "partitions cannot be null");
      return this;
    }

    /**
     * Sets the primitive types.
     *
     * @param primitiveTypes the primitive types
     * @return the Atomix builder
     * @throws NullPointerException if the primitive types is {@code null}
     */
    public Builder withPrimitiveTypes(PrimitiveType... primitiveTypes) {
      return withPrimitiveTypes(Arrays.asList(primitiveTypes));
    }

    /**
     * Sets the primitive types.
     *
     * @param primitiveTypes the primitive types
     * @return the Atomix builder
     * @throws NullPointerException if the primitive types is {@code null}
     */
    public Builder withPrimitiveTypes(Collection<PrimitiveType> primitiveTypes) {
      primitiveTypes.forEach(type -> this.primitiveTypes.register(type));
      return this;
    }

    /**
     * Adds a primitive type.
     *
     * @param primitiveType the primitive type to add
     * @return the Atomix builder
     * @throws NullPointerException if the primitive type is {@code null}
     */
    public Builder addPrimitiveType(PrimitiveType primitiveType) {
      primitiveTypes.register(primitiveType);
      return this;
    }

    /**
     * Sets the path to the data directory.
     *
     * @param dataDir the path to the replica's data directory
     * @return the replica builder
     */
    public Builder withDataDir(File dataDir) {
      this.dataDir = checkNotNull(dataDir, "dataDir cannot be null");
      return this;
    }

    @Override
    public Atomix build() {
      ManagedMessagingService messagingService = buildMessagingService();
      ManagedClusterService clusterService = buildClusterService(messagingService);
      ManagedClusterCommunicationService clusterCommunicator = buildClusterCommunicationService(clusterService, messagingService);
      ManagedClusterEventService clusterEventService = buildClusterEventService(clusterService, clusterCommunicator);
      ManagedPartitionService partitionService = buildPartitionService(clusterCommunicator);
      PrimitivesService primitives = buildPrimitiveService(partitionService);
      ManagedRestService restService = buildRestService(clusterService, clusterCommunicator, clusterEventService, primitives);
      return new Atomix(
          clusterService,
          messagingService,
          clusterCommunicator,
          clusterEventService,
          partitionService,
          restService,
          primitives);
    }

    /**
     * Builds a default messaging service.
     */
    private ManagedMessagingService buildMessagingService() {
      return NettyMessagingService.builder()
          .withName(name)
          .withEndpoint(localNode.endpoint())
          .build();
    }

    /**
     * Builds a cluster service.
     */
    private ManagedClusterService buildClusterService(MessagingService messagingService) {
      return new DefaultClusterService(ClusterMetadata.builder()
          .withLocalNode(localNode)
          .withBootstrapNodes(bootstrapNodes)
          .build(), messagingService);
    }

    /**
     * Builds a cluster communication service.
     */
    private ManagedClusterCommunicationService buildClusterCommunicationService(
        ClusterService clusterService, MessagingService messagingService) {
      return new DefaultClusterCommunicationService(clusterService, messagingService);
    }

    /**
     * Builds a cluster event service.
     */
    private ManagedClusterEventService buildClusterEventService(
        ClusterService clusterService, ClusterCommunicationService clusterCommunicator) {
      return new DefaultClusterEventService(clusterService, clusterCommunicator);
    }

    /**
     * Builds a partition service.
     */
    private ManagedPartitionService buildPartitionService(ClusterCommunicationService clusterCommunicator) {
      File partitionsDir = new File(this.dataDir, "partitions");
      Collection<ManagedPartition> partitions = buildPartitions().stream()
          .map(p -> new RaftPartition(localNode.id(), p, clusterCommunicator, primitiveTypes, new File(partitionsDir, p.id().toString())))
          .collect(Collectors.toList());
      return new DefaultPartitionService(partitions);
    }

    /**
     * Builds a primitive service.
     */
    private PrimitivesService buildPrimitiveService(PartitionService partitionService) {
      return new PartitionedPrimitivesService(partitionService);
    }

    /**
     * Builds a REST service.
     */
    private ManagedRestService buildRestService(
        ClusterService clusterService,
        ClusterCommunicationService communicationService,
        ClusterEventService eventService,
        PrimitivesService primitivesService) {
      return httpPort > 0 ? new VertxRestService(localNode.endpoint().host().getHostAddress(), httpPort, clusterService, communicationService, eventService, primitivesService) : null;
    }

    /**
     * Builds the cluster partitions.
     */
    private Collection<PartitionMetadata> buildPartitions() {
      if (partitions != null) {
        return partitions;
      }

      if (numPartitions == 0) {
        numPartitions = bootstrapNodes.size();
      }

      if (partitionSize == 0) {
        partitionSize = Math.min(bootstrapNodes.size(), 3);
      }

      List<Node> sorted = new ArrayList<>(bootstrapNodes);
      sorted.sort(Comparator.comparing(Node::id));

      Set<PartitionMetadata> partitions = Sets.newHashSet();
      for (int i = 0; i < numPartitions; i++) {
        Set<NodeId> set = new HashSet<>(partitionSize);
        for (int j = 0; j < partitionSize; j++) {
          set.add(sorted.get((i + j) % numPartitions).id());
        }
        partitions.add(new PartitionMetadata(PartitionId.from((i + 1)), set));
      }
      return partitions;
    }
  }
}
