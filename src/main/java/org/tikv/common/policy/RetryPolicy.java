/*
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tikv.common.policy;

import com.google.common.collect.ImmutableSet;
import io.grpc.Status;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import java.util.concurrent.Callable;
import org.tikv.common.exception.GrpcException;
import org.tikv.common.operation.ErrorHandler;
import org.tikv.common.util.BackOffer;
import org.tikv.common.util.ConcreteBackOffer;

public abstract class RetryPolicy<RespT> {
  BackOffer backOffer = ConcreteBackOffer.newCopNextMaxBackOff();
  public static final Histogram GRPC_SINGLE_REQUEST_LATENCY =
      Histogram.build()
          .name("client_java_grpc_single_requests_latency")
          .help("grpc request latency.")
          .labelNames("type")
          .register();
  public static final Counter GRPC_REQUEST_RETRY_NUM =
      Counter.build()
          .name("client_java_grpc_requests_retry_num")
          .help("grpc request retry num.")
          .labelNames("type")
          .register();

  // handles PD and TiKV's error.
  private final ErrorHandler<RespT> handler;

  private final ImmutableSet<Status.Code> unrecoverableStatus =
      ImmutableSet.of(
          Status.Code.ALREADY_EXISTS, Status.Code.PERMISSION_DENIED,
          Status.Code.INVALID_ARGUMENT, Status.Code.NOT_FOUND,
          Status.Code.UNIMPLEMENTED, Status.Code.OUT_OF_RANGE,
          Status.Code.UNAUTHENTICATED, Status.Code.CANCELLED);

  RetryPolicy(ErrorHandler<RespT> handler) {
    this.handler = handler;
  }

  private void rethrowNotRecoverableException(Exception e) {
    Status status = Status.fromThrowable(e);
    if (unrecoverableStatus.contains(status.getCode())) {
      throw new GrpcException(e);
    }
  }

  public RespT callWithRetry(Callable<RespT> proc, String methodName) {
    while (true) {
      RespT result = null;
      try {
        // add single request duration histogram
        Histogram.Timer requestTimer = GRPC_SINGLE_REQUEST_LATENCY.labels(methodName).startTimer();
        try {
          result = proc.call();
        } finally {
          requestTimer.observeDuration();
        }
      } catch (Exception e) {
        rethrowNotRecoverableException(e);
        // Handle request call error
        boolean retry = handler.handleRequestError(backOffer, e);
        if (retry) {
          GRPC_REQUEST_RETRY_NUM.labels(methodName).inc();
          continue;
        } else {
          return result;
        }
      }

      // Handle response error
      if (handler != null) {
        boolean retry = handler.handleResponseError(backOffer, result);
        if (retry) {
          GRPC_REQUEST_RETRY_NUM.labels(methodName).inc();
          continue;
        }
      }
      return result;
    }
  }

  public interface Builder<T> {
    RetryPolicy<T> create(ErrorHandler<T> handler);
  }
}
