/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.integrations.source.jdbc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.airbyte.commons.json.Jsons;
import io.airbyte.integrations.base.AirbyteStreamNameNamespacePair;
import io.airbyte.integrations.source.jdbc.models.JdbcState;
import io.airbyte.integrations.source.jdbc.models.JdbcStreamState;
import io.airbyte.protocol.models.AirbyteStateMessage;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the state machine for the state of jdbc source implementations.
 */
public class JdbcStateManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(JdbcStateManager.class);

  private final Map<AirbyteStreamNameNamespacePair, CursorInfo> pairToCursorInfo;
  private Boolean isCdc;
  private final JdbcCdcStateManager cdcStateManager;

  public static JdbcState emptyState() {
    return new JdbcState();
  }

  public JdbcStateManager(JdbcState serialized, ConfiguredAirbyteCatalog catalog) {
    this.cdcStateManager = new JdbcCdcStateManager(serialized.getCdcState());
    this.isCdc = serialized.getCdc();
    if (serialized.getCdc() == null) {
      this.isCdc = false;
    }

    pairToCursorInfo =
        new ImmutableMap.Builder<AirbyteStreamNameNamespacePair, CursorInfo>().putAll(createCursorInfoMap(serialized, catalog)).build();
  }

  private static Map<AirbyteStreamNameNamespacePair, CursorInfo> createCursorInfoMap(JdbcState serialized, ConfiguredAirbyteCatalog catalog) {
    final Set<AirbyteStreamNameNamespacePair> allStreamNames = catalog.getStreams()
        .stream()
        .map(ConfiguredAirbyteStream::getStream)
        .map(AirbyteStreamNameNamespacePair::fromAirbyteSteam)
        .collect(Collectors.toSet());
    allStreamNames.addAll(serialized.getStreams().stream().map(JdbcStateManager::toAirbyteStreamNameNamespacePair).collect(Collectors.toSet()));

    final Map<AirbyteStreamNameNamespacePair, CursorInfo> localMap = new HashMap<>();
    final Map<AirbyteStreamNameNamespacePair, JdbcStreamState> pairToState = serialized.getStreams()
        .stream()
        .collect(Collectors.toMap(JdbcStateManager::toAirbyteStreamNameNamespacePair, a -> a));
    final Map<AirbyteStreamNameNamespacePair, ConfiguredAirbyteStream> pairToConfiguredAirbyteStream = catalog.getStreams().stream()
        .collect(Collectors.toMap(AirbyteStreamNameNamespacePair::fromConfiguredAirbyteSteam, s -> s));

    for (final AirbyteStreamNameNamespacePair pair : allStreamNames) {
      final Optional<JdbcStreamState> stateOptional = Optional.ofNullable(pairToState.get(pair));
      final Optional<ConfiguredAirbyteStream> streamOptional = Optional.ofNullable(pairToConfiguredAirbyteStream.get(pair));
      localMap.put(pair, createCursorInfoForStream(pair, stateOptional, streamOptional));
    }

    return localMap;
  }

  private static AirbyteStreamNameNamespacePair toAirbyteStreamNameNamespacePair(JdbcStreamState state) {
    return new AirbyteStreamNameNamespacePair(state.getStreamName(), state.getStreamNamespace());
  }

  @VisibleForTesting
  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  static CursorInfo createCursorInfoForStream(AirbyteStreamNameNamespacePair pair,
                                              Optional<JdbcStreamState> stateOptional,
                                              Optional<ConfiguredAirbyteStream> streamOptional) {
    final String originalCursorField = stateOptional
        .map(JdbcStreamState::getCursorField)
        .flatMap(f -> f.size() > 0 ? Optional.of(f.get(0)) : Optional.empty())
        .orElse(null);
    final String originalCursor = stateOptional.map(JdbcStreamState::getCursor).orElse(null);

    final String cursor;
    final String cursorField;

    // if cursor field is set in catalog.
    if (streamOptional.map(ConfiguredAirbyteStream::getCursorField).isPresent()) {
      cursorField = streamOptional
          .map(ConfiguredAirbyteStream::getCursorField)
          .flatMap(f -> f.size() > 0 ? Optional.of(f.get(0)) : Optional.empty())
          .orElse(null);
      // if cursor field is set in state.
      if (stateOptional.map(JdbcStreamState::getCursorField).isPresent()) {
        // if cursor field in catalog and state are the same.
        if (stateOptional.map(JdbcStreamState::getCursorField).equals(streamOptional.map(ConfiguredAirbyteStream::getCursorField))) {
          cursor = stateOptional.map(JdbcStreamState::getCursor).orElse(null);
          LOGGER.info("Found matching cursor in state. Stream: {}. Cursor Field: {} Value: {}", pair, cursorField, cursor);
          // if cursor field in catalog and state are different.
        } else {
          cursor = null;
          LOGGER.info(
              "Found cursor field. Does not match previous cursor field. Stream: {}. Original Cursor Field: {}. New Cursor Field: {}. Resetting cursor value.",
              pair, originalCursorField, cursorField);
        }
        // if cursor field is not set in state but is set in catalog.
      } else {
        LOGGER.info("No cursor field set in catalog but not present in state. Stream: {}, New Cursor Field: {}. Resetting cursor value", pair,
            cursorField);
        cursor = null;
      }
      // if cursor field is not set in catalog.
    } else {
      LOGGER.info(
          "Cursor field set in state but not present in catalog. Stream: {}. Original Cursor Field: {}. Original value: {}. Resetting cursor.",
          pair, originalCursorField, originalCursor);
      cursorField = null;
      cursor = null;
    }

    return new CursorInfo(originalCursorField, originalCursor, cursorField, cursor);
  }

  private Optional<CursorInfo> getCursorInfo(AirbyteStreamNameNamespacePair pair) {
    return Optional.ofNullable(pairToCursorInfo.get(pair));
  }

  public Optional<String> getOriginalCursorField(AirbyteStreamNameNamespacePair pair) {
    return getCursorInfo(pair).map(CursorInfo::getOriginalCursorField);
  }

  public Optional<String> getOriginalCursor(AirbyteStreamNameNamespacePair pair) {
    return getCursorInfo(pair).map(CursorInfo::getOriginalCursor);
  }

  public Optional<String> getCursorField(AirbyteStreamNameNamespacePair pair) {
    return getCursorInfo(pair).map(CursorInfo::getCursorField);
  }

  public Optional<String> getCursor(AirbyteStreamNameNamespacePair pair) {
    return getCursorInfo(pair).map(CursorInfo::getCursor);
  }

  synchronized public AirbyteStateMessage updateAndEmit(AirbyteStreamNameNamespacePair pair, String cursor) {
    // cdc file gets updated by debezium so the "update" part is a no op.
    if (!isCdc) {
      final Optional<CursorInfo> cursorInfo = getCursorInfo(pair);
      Preconditions.checkState(cursorInfo.isPresent(), "Could not find cursor information for stream: " + pair);
      cursorInfo.get().setCursor(cursor);
    }

    return toState();
  }

  public void setIsCdc(boolean isCdc) {
    if (this.isCdc == null) {
      this.isCdc = isCdc;
    } else {
      Preconditions.checkState(this.isCdc == isCdc, "attempt to set cdc to {}, but is already set to {}.", isCdc, this.isCdc);
    }
  }

  public JdbcCdcStateManager getCdcStateManager() {
    return cdcStateManager;
  }

  public AirbyteStateMessage emit() {
    return toState();
  }

  private AirbyteStateMessage toState() {
    final JdbcState jdbcState = new JdbcState()
        .withCdc(isCdc)
        .withStreams(pairToCursorInfo.entrySet().stream()
            .sorted(Entry.comparingByKey()) // sort by stream name then namespace for sanity.
            .map(e -> new JdbcStreamState()
                .withStreamName(e.getKey().getName())
                .withStreamNamespace(e.getKey().getNamespace())
                .withCursorField(e.getValue().getCursorField() == null ? Collections.emptyList() : Lists.newArrayList(e.getValue().getCursorField()))
                .withCursor(e.getValue().getCursor()))
            .collect(Collectors.toList()))
        .withCdcState(cdcStateManager.getCdcState());

    return new AirbyteStateMessage().withData(Jsons.jsonNode(jdbcState));
  }

  @VisibleForTesting
  static class CursorInfo {

    private final String originalCursorField;
    private final String originalCursor;

    private final String cursorField;
    private String cursor;

    public CursorInfo(String originalCursorField, String originalCursor, String cursorField, String cursor) {
      this.originalCursorField = originalCursorField;
      this.originalCursor = originalCursor;
      this.cursorField = cursorField;
      this.cursor = cursor;
    }

    public String getOriginalCursorField() {
      return originalCursorField;
    }

    public String getOriginalCursor() {
      return originalCursor;
    }

    public String getCursorField() {
      return cursorField;
    }

    public String getCursor() {
      return cursor;
    }

    @SuppressWarnings("UnusedReturnValue")
    public CursorInfo setCursor(String cursor) {
      this.cursor = cursor;
      return this;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      CursorInfo that = (CursorInfo) o;
      return Objects.equals(originalCursorField, that.originalCursorField) && Objects.equals(originalCursor, that.originalCursor)
          && Objects.equals(cursorField, that.cursorField) && Objects.equals(cursor, that.cursor);
    }

    @Override
    public int hashCode() {
      return Objects.hash(originalCursorField, originalCursor, cursorField, cursor);
    }

    @Override
    public String toString() {
      return "CursorInfo{" +
          "originalCursorField='" + originalCursorField + '\'' +
          ", originalCursor='" + originalCursor + '\'' +
          ", cursorField='" + cursorField + '\'' +
          ", cursor='" + cursor + '\'' +
          '}';
    }

  }

}
