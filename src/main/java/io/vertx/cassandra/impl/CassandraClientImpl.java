/*
 * Copyright 2018 The Vert.x Community.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.vertx.cassandra.impl;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.NettyOptions;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.Statement;
import com.google.common.util.concurrent.ListenableFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Timer;
import io.vertx.cassandra.CassandraClient;
import io.vertx.cassandra.CassandraClientOptions;
import io.vertx.cassandra.ResultSet;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.VertxInternal;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

public class CassandraClientImpl implements CassandraClient {

  VertxInternal vertx;
  CassandraClientOptions options;
  AtomicReference<Session> session = new AtomicReference<>(null);

  public CassandraClientImpl() {
  }

  public CassandraClientImpl(Vertx vertx, CassandraClientOptions cassandraClientOptions) {
    this.vertx = (VertxInternal) vertx;
    this.options = cassandraClientOptions;
  }

  @Override
  public CassandraClient connect() {
    return connect(null);
  }

  @Override
  public CassandraClient connect(Handler<AsyncResult<Void>> connectHandler) {
    return connect(null, connectHandler);
  }

  @Override
  public CassandraClient connect(String keyspace, Handler<AsyncResult<Void>> connectHandler) {
    session.set(null);
    Cluster.Builder builder = Cluster.builder();

    if (options.contactPoints().isEmpty()) {
      builder.addContactPoint(CassandraClientOptions.DEFAULT_HOST);
    } else {
      for (String contactPoint : options.contactPoints()) {
        builder.addContactPoint(contactPoint);
      }
    }

    Cluster build = builder.withNettyOptions(
      new NettyOptions() {
        @Override
        public EventLoopGroup eventLoopGroup(ThreadFactory threadFactory) {
          return vertx.getEventLoopGroup();
        }

        @Override
        public void onClusterClose(EventLoopGroup eventLoopGroup) {
          // it is important to not do anything here
          // because the default behaviour is to shutdown the Vert.x event loop group
        }

        @Override
        public Timer timer(ThreadFactory threadFactory) {
          // we don't wan't to use the Vert.x event loop group here
          // since the timer thread is got blocked between events(via sleep call)
          return super.timer(threadFactory);
        }

        @Override
        public void onClusterClose(Timer timer) {
          // it is also fine did not do anything here
          // since Vert.x threads is not related to the timer
          super.onClusterClose(timer);
        }
      })
      .withPort(options.port())
      .build();
    ListenableFuture<Session> connectGuavaFuture;
    if (keyspace == null) {
      connectGuavaFuture = build.connectAsync();
    } else {
      connectGuavaFuture = build.connectAsync(keyspace);
    }

    Future<Session> sessionFuture = Util.toVertxFuture(connectGuavaFuture, vertx);

    sessionFuture.setHandler(event -> {
      if (event.succeeded()) {
        session.set(event.result());
        if (connectHandler != null) {
          connectHandler.handle(Future.succeededFuture());
        }
      } else {
        if (connectHandler != null) {
          connectHandler.handle(Future.failedFuture(event.cause()));
        }
      }
    });

    return this;
  }

  @Override
  public CassandraClient execute(String query, Handler<AsyncResult<ResultSet>> resultHandler){
    return execute(new SimpleStatement(query), resultHandler);
  }

  @Override
  public CassandraClient execute(Statement statement, Handler<AsyncResult<ResultSet>> resultHandler) {
    Session session = this.session.get();
    if (session != null) {
      ResultSetFuture resultSetFuture = session.executeAsync(statement);
      Future<com.datastax.driver.core.ResultSet> vertxExecuteFuture = Util.toVertxFuture(resultSetFuture, vertx);
      vertxExecuteFuture.setHandler(executionResult -> {
        if (executionResult.succeeded()) {
          if (resultHandler != null) {
            resultHandler.handle(Future.succeededFuture(new ResultSetImpl(executionResult.result())));
          }
        } else {
          if (resultHandler != null) {
            resultHandler.handle(Future.failedFuture(executionResult.cause()));
          }
        }
      });
    } else {
      if (resultHandler != null) {
        resultHandler.handle(Future.failedFuture("In order to execute the statement, you should be connected"));
      }
    }
    return this;
  }

  @Override
  public CassandraClient disconnect() {
    return disconnect(null);
  }

  @Override
  public CassandraClient prepare(String query, Handler<AsyncResult<PreparedStatement>> resultHandler) {
    Session session = this.session.get();
    if (session != null) {
      ListenableFuture<com.datastax.driver.core.PreparedStatement> preparedFuture = session.prepareAsync(query);
      Future<com.datastax.driver.core.PreparedStatement> vertxExecuteFuture = Util.toVertxFuture(preparedFuture, vertx);
      vertxExecuteFuture.setHandler(executionResult -> {
        if (executionResult.succeeded()) {
          if (resultHandler != null) {
            resultHandler.handle(Future.succeededFuture(executionResult.result()));
          }
        } else {
          if (resultHandler != null) {
            resultHandler.handle(Future.failedFuture(executionResult.cause()));
          }
        }
      });
    } else {
      if (resultHandler != null) {
        resultHandler.handle(Future.failedFuture("In order to prepare the query, you should be connected"));
      }
    }
    return this;
  }

  @Override
  public CassandraClient disconnect(Handler<AsyncResult<Void>> disconnectHandler) {
    Session session = this.session.get();
    if (session != null) {
      Future<Void> vertxFuture = Util.toVertxFuture(session.closeAsync(), vertx);
      vertxFuture.setHandler(event -> {
        if (event.succeeded()) {
          if (disconnectHandler != null) {
            disconnectHandler.handle(Future.succeededFuture());
          }
        } else {
          if (disconnectHandler != null) {
            disconnectHandler.handle(Future.failedFuture(event.cause()));
          }
        }
      });
    }
    return this;
  }
}
