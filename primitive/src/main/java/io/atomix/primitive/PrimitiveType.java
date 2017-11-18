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
package io.atomix.primitive;

import io.atomix.primitive.partition.PartitionService;
import io.atomix.primitive.service.PrimitiveService;
import io.atomix.utils.Identifier;

/**
 * Raft service type.
 */
public interface PrimitiveType<B extends DistributedPrimitiveBuilder<B, S, A>, S extends SyncPrimitive, A extends AsyncPrimitive> extends Identifier<String> {

  /**
   * Returns the primitive type name.
   *
   * @return the primitive type name
   */
  @Override
  String id();

  /**
   * Returns a new primitive service instance.
   *
   * @return a new primitive service instance
   */
  PrimitiveService newService();

  /**
   * Returns a new primitive builder for the given partition.
   *
   * @param name the primitive name
   * @param client the primitive client
   * @return the primitive builder
   */
  B newPrimitiveBuilder(String name, PrimitiveClient client);

  /**
   * Returns a new primitive builder for the given partitions.
   *
   * @param name the primitive name
   * @param partitions the partitions for which to return a primitive builder
   * @return a primitive builder for the given partitions
   */
  default B newPrimitiveBuilder(String name, PartitionService partitions) {
    return newPrimitiveBuilder(name, partitions.getPartition(name).getPrimitiveClient());
  }

}
