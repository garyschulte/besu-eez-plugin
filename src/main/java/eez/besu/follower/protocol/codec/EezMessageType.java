/*
 * Copyright contributors to besu-eez-plugin.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package eez.besu.follower.protocol.codec;

import java.util.Optional;

/**
 * EEZ logical-stream message types (BLOB_FORMAT_SPEC §2, stream version {@code 0x00}). Type byte
 * {@code 0} is reserved as invalid so zero padding never parses as a message.
 */
public enum EezMessageType {
  CLOSE_BLOB_STREAM(1),
  CHAIN_OPERATION(2),
  INITIATE_CROSS_CHAIN_TRANSACTION(3),
  CALL(4),
  STATIC_CALL(5),
  RETURN_SUCCESS(6),
  RETURN_FAIL(7),
  SNAPSHOT(8),
  REVERT(9),
  FINISH_CROSS_CHAIN_TRANSACTION(10);

  private static final EezMessageType[] BY_CODE = new EezMessageType[11];

  static {
    for (final EezMessageType type : values()) {
      BY_CODE[type.code] = type;
    }
  }

  private final int code;

  EezMessageType(final int code) {
    this.code = code;
  }

  /** The wire type byte. */
  public int code() {
    return code;
  }

  /** Looks up an assigned type byte; empty for {@code 0} and {@code 11..255}. */
  public static Optional<EezMessageType> fromCode(final int code) {
    return code > 0 && code < BY_CODE.length ? Optional.of(BY_CODE[code]) : Optional.empty();
  }
}
