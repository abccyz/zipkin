/**
 * Copyright 2015-2017 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.storage.elasticsearch.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import okio.BufferedSource;
import zipkin.DependencyLink;
import zipkin.internal.Pair;
import zipkin.internal.Util;
import zipkin.internal.v2.Call;
import zipkin.internal.v2.Span;
import zipkin.internal.v2.storage.QueryRequest;
import zipkin.internal.v2.storage.SpanStore;
import zipkin.storage.Callback;
import zipkin.storage.elasticsearch.http.internal.client.Aggregation;
import zipkin.storage.elasticsearch.http.internal.client.HttpCall;
import zipkin.storage.elasticsearch.http.internal.client.HttpCall.BodyConverter;
import zipkin.storage.elasticsearch.http.internal.client.SearchCallFactory;
import zipkin.storage.elasticsearch.http.internal.client.SearchRequest;

import static java.util.Arrays.asList;

final class ElasticsearchHttpSpanStore implements SpanStore {

  static final String SPAN = "span";
  static final String DEPENDENCY = "dependency";
  /** To not produce unnecessarily long queries, we don't look back further than first ES support */
  static final long EARLIEST_MS = 1456790400000L; // March 2016

  final SearchCallFactory search;
  final String[] allSpanIndices;
  final IndexNameFormatter indexNameFormatter;
  final boolean strictTraceId;
  final int namesLookback;

  ElasticsearchHttpSpanStore(ElasticsearchHttpStorage es) {
    this.search = new SearchCallFactory(es.http());
    this.allSpanIndices = new String[] {es.indexNameFormatter().formatType(SPAN)};
    this.indexNameFormatter = es.indexNameFormatter();
    this.strictTraceId = es.strictTraceId();
    this.namesLookback = es.namesLookback();
  }

  @Override public Call<List<List<Span>>> getTraces(QueryRequest request) {
    long endMillis = request.endTs();
    long beginMillis = Math.max(endMillis - request.lookback(), EARLIEST_MS);

    SearchRequest.Filters filters = new SearchRequest.Filters();
    filters.addRange("timestamp_millis", beginMillis, endMillis);
    if (request.serviceName() != null) {
      filters.addTerm("localEndpoint.serviceName", request.serviceName());
    }

    if (request.spanName() != null) {
      filters.addTerm("name", request.spanName());
    }

    for (Map.Entry<String, String> kv : request.annotationQuery().entrySet()) {
      if (kv.getValue().isEmpty()) {
        filters.addTerm("_q", kv.getKey());
      } else {
        filters.addTerm("_q", kv.getKey() + "=" + kv.getValue());
      }
    }

    if (request.minDuration() != null) {
      filters.addRange("duration", request.minDuration(), request.maxDuration());
    }

    // We need to filter to traces that contain at least one span that matches the request,
    // but the zipkin API is supposed to order traces by first span, regardless of if it was
    // filtered or not. This is not possible without either multiple, heavyweight queries
    // or complex multiple indexing, defeating much of the elegance of using elasticsearch for this.
    // So we fudge and order on the first span among the filtered spans - in practice, there should
    // be no significant difference in user experience since span start times are usually very
    // close to each other in human time.
    Aggregation traceIdTimestamp = Aggregation.terms("traceId", request.limit())
        .addSubAggregation(Aggregation.min("timestamp_millis"))
        .orderBy("timestamp_millis", "desc");

    List<String> indices = indexNameFormatter.formatTypeAndRange(SPAN, beginMillis, endMillis);
    if (indices.isEmpty()) return Call.emptyList();

    SearchRequest esRequest = SearchRequest.create(indices)
        .filters(filters).addAggregation(traceIdTimestamp);

    HttpCall<List<String>> traceIdsCall = search.newCall(esRequest, BodyConverters.SORTED_KEYS);

    // When we receive span results, we need to group them by trace ID
    BodyConverter<List<List<Span>>> converter = content -> {
      BodyConverter<List<Span>> input = BodyConverters.SPANS;
      List<List<Span>> traces = groupByTraceId(input.convert(content), strictTraceId);

      // Due to tokenization of the trace ID, our matches are imprecise on Span.traceIdHigh
      for (Iterator<List<Span>> trace = traces.iterator(); trace.hasNext(); ) {
        List<Span> next = trace.next();
        if (next.get(0).traceIdHigh() != 0 && !request.test(next)) {
          trace.remove();
        }
      }
      return traces;
    };

    return traceIdsCall.flatMap(input -> {
      if (input == null || input.isEmpty()) return Call.emptyList();

      SearchRequest getTraces = SearchRequest.create(indices).terms("traceId", input);
      return search.newCall(getTraces, converter);
    });
  }

  @Override public Call<List<Span>> getTrace(long traceIdHigh, long traceIdLow) {
    String traceIdHex = Util.toLowerHex(strictTraceId ? traceIdHigh : 0L, traceIdLow);

    SearchRequest request = SearchRequest.create(asList(allSpanIndices))
        .term("traceId", traceIdHex);

    return search.newCall(request, BodyConverters.SPANS);
  }

  @Override public Call<List<String>> getServiceNames() {
    long endMillis = System.currentTimeMillis();
    long beginMillis = endMillis - namesLookback;

    List<String> indices = indexNameFormatter.formatTypeAndRange(SPAN, beginMillis, endMillis);
    if (indices.isEmpty()) return Call.emptyList();

    // Service name queries include both local and remote endpoints. This is different than
    // Span name, as a span name can only be on a local endpoint.
    SearchRequest.Filters filters = new SearchRequest.Filters();
    filters.addRange("timestamp_millis", beginMillis, endMillis);
    SearchRequest request = SearchRequest.create(indices)
        .filters(filters)
        .addAggregation(Aggregation.terms("localEndpoint.serviceName", Integer.MAX_VALUE))
        .addAggregation(Aggregation.terms("remoteEndpoint.serviceName", Integer.MAX_VALUE));
    return search.newCall(request, BodyConverters.SORTED_KEYS);
  }

  @Override public Call<List<String>> getSpanNames(String serviceName) {
    if (serviceName == null || "".equals(serviceName)) return Call.emptyList();

    long endMillis = System.currentTimeMillis();
    long beginMillis = endMillis - namesLookback;

    List<String> indices = indexNameFormatter.formatTypeAndRange(SPAN, beginMillis, endMillis);
    if (indices.isEmpty()) return Call.emptyList();

    // A span name is only valid on a local endpoint, as a span name is defined locally
    SearchRequest.Filters filters = new SearchRequest.Filters()
        .addRange("timestamp_millis", beginMillis, endMillis)
        .addTerm("localEndpoint.serviceName", serviceName.toLowerCase(Locale.ROOT));

    SearchRequest request = SearchRequest.create(indices)
        .filters(filters)
        .addAggregation(Aggregation.terms("name", Integer.MAX_VALUE));

    return search.newCall(request, BodyConverters.SORTED_KEYS);
  }

  @Override public Call<List<DependencyLink>> getDependencies(long endTs, long lookback) {
    long beginMillis = Math.max(endTs - lookback, EARLIEST_MS);

    // We just return all dependencies in the days that fall within endTs and lookback as
    // dependency links themselves don't have timestamps.
    List<String> indices = indexNameFormatter.formatTypeAndRange(DEPENDENCY, beginMillis, endTs);
    if (indices.isEmpty()) return Call.emptyList();

    return search.newCall(SearchRequest.create(indices), BodyConverters.DEPENDENCY_LINKS);
  }

  static List<List<Span>> groupByTraceId(Collection<Span> input, boolean strictTraceId) {
    if (input == null || input.isEmpty()) return Collections.emptyList();

    Map<Pair<Long>, List<Span>> groupedByTraceId = new LinkedHashMap<>();
    for (Span span : input) {
      Pair<Long> traceId = Pair.create(strictTraceId ? span.traceIdHigh() : 0L, span.traceId());
      if (!groupedByTraceId.containsKey(traceId)) {
        groupedByTraceId.put(traceId, new LinkedList<>());
      }
      groupedByTraceId.get(traceId).add(span);
    }
    return new ArrayList<>(groupedByTraceId.values());
  }
}
