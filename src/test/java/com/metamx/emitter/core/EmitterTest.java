/*
 * Copyright 2012 Metamarkets Group Inc.
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

package com.metamx.emitter.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.metamx.emitter.service.UnitEvent;
import com.metamx.http.client.GoHandler;
import com.metamx.http.client.GoHandlers;
import com.metamx.http.client.MockHttpClient;
import com.metamx.http.client.Request;
import com.metamx.http.client.response.HttpResponseHandler;
import com.metamx.http.client.response.StatusResponseHandler;
import com.metamx.http.client.response.StatusResponseHolder;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 */
public class EmitterTest
{
  private static final ObjectMapper jsonMapper = new ObjectMapper();
  public static String TARGET_URL = "http://metrics.foo.bar/";

  MockHttpClient httpClient;
  HttpPostEmitter emitter;

  public static StatusResponseHolder okResponse()
  {
    return new StatusResponseHolder(
        new HttpResponseStatus(201, "Created"),
        new StringBuilder("Yay")
    );
  }

  @Before
  public void setUp() throws Exception
  {
    httpClient = new MockHttpClient();
  }

  @After
  public void tearDown() throws Exception
  {
    if (emitter != null) {
      emitter.close();
    }
  }

  private HttpPostEmitter timeBasedEmitter(long timeInMillis)
  {
    HttpPostEmitter emitter = new HttpPostEmitter(
        new HttpEmitterConfig(timeInMillis, Integer.MAX_VALUE, TARGET_URL),
        httpClient,
        jsonMapper
    );
    emitter.start();
    return emitter;
  }

  private HttpPostEmitter sizeBasedEmitter(int size)
  {
    HttpPostEmitter emitter = new HttpPostEmitter(
        new HttpEmitterConfig(Long.MAX_VALUE, size, TARGET_URL),
        httpClient,
        jsonMapper
    );
    emitter.start();
    return emitter;
  }

  private HttpPostEmitter manualFlushEmitterWithBasicAuthenticationAndNewlineSeparating(String authentication)
  {
    HttpPostEmitter emitter = new HttpPostEmitter(
        new HttpEmitterConfig(
            Long.MAX_VALUE,
            Integer.MAX_VALUE,
            TARGET_URL,
            authentication,
            BatchingStrategy.NEWLINES,
            1024 * 1024,
            100 * 1024 * 1024
        ),
        httpClient,
        jsonMapper
    );
    emitter.start();
    return emitter;
  }

  private HttpPostEmitter manualFlushEmitterWithBatchSizeAndBufferSize(int batchSize, long bufferSize)
  {
    HttpPostEmitter emitter = new HttpPostEmitter(
        new HttpEmitterConfig(Long.MAX_VALUE, Integer.MAX_VALUE, TARGET_URL, batchSize, bufferSize),
        httpClient,
        jsonMapper
    );
    emitter.start();
    return emitter;
  }

  @Test
  public void testSanity() throws Exception
  {
    final List<UnitEvent> events = Arrays.asList(
        new UnitEvent("test", 1),
        new UnitEvent("test", 2)
    );
    emitter = sizeBasedEmitter(2);

    httpClient.setGoHandler(
        new GoHandler()
        {
          @Override
          public <Intermediate, Final> ListenableFuture<Final> go(Request request, HttpResponseHandler<Intermediate, Final> handler) throws Exception
          {
            Assert.assertEquals(new URL(TARGET_URL), request.getUrl());
            Assert.assertEquals(
                ImmutableList.of("application/json"),
                request.getHeaders().get(HttpHeaders.Names.CONTENT_TYPE)
            );
            Assert.assertEquals(
                String.format(
                    "[%s,%s]\n",
                    jsonMapper.writeValueAsString(events.get(0)),
                    jsonMapper.writeValueAsString(events.get(1))
                ),
                request.getContent().toString(Charsets.UTF_8)
            );
            Assert.assertTrue(
                "handler is a StatusResponseHandler",
                handler instanceof StatusResponseHandler
            );

            return Futures.immediateFuture((Final) okResponse());
          }
        }.times(1)
    );

    for (UnitEvent event : events) {
      emitter.emit(event);
    }
    waitForEmission(emitter);
    closeNoFlush(emitter);
    Assert.assertTrue(httpClient.succeeded());
  }

  @Test
  public void testSizeBasedEmission() throws Exception
  {
    emitter = sizeBasedEmitter(3);

    httpClient.setGoHandler(GoHandlers.failingHandler());
    emitter.emit(new UnitEvent("test", 1));
    emitter.emit(new UnitEvent("test", 2));

    httpClient.setGoHandler(GoHandlers.passingHandler(okResponse()).times(1));
    emitter.emit(new UnitEvent("test", 3));
    waitForEmission(emitter);

    httpClient.setGoHandler(GoHandlers.failingHandler());
    emitter.emit(new UnitEvent("test", 4));
    emitter.emit(new UnitEvent("test", 5));

    closeAndExpectFlush(emitter);
    Assert.assertTrue(httpClient.succeeded());
  }

  @Test
  public void testTimeBasedEmission() throws Exception
  {
    final int timeBetweenEmissions = 100;
    emitter = timeBasedEmitter(timeBetweenEmissions);

    final CountDownLatch latch = new CountDownLatch(1);

    httpClient.setGoHandler(
        new GoHandler()
        {
          @Override
          public <Intermediate, Final> ListenableFuture<Final> go(Request intermediateFinalRequest, HttpResponseHandler<Intermediate, Final> handler)
              throws Exception
          {
            latch.countDown();
            return Futures.immediateFuture((Final) okResponse());
          }
        }.times(1)
    );

    long emitTime = System.currentTimeMillis();
    emitter.emit(new UnitEvent("test", 1));

    latch.await();
    long timeWaited = System.currentTimeMillis() - emitTime;
    Assert.assertTrue(
        String.format("timeWaited[%s] !< %s", timeWaited, timeBetweenEmissions * 2),
        timeWaited < timeBetweenEmissions * 2
    );

    waitForEmission(emitter);

    final CountDownLatch thisLatch = new CountDownLatch(1);
    httpClient.setGoHandler(
        new GoHandler()
        {
          @Override
          public <Intermediate, Final> ListenableFuture<Final> go(Request intermediateFinalRequest, HttpResponseHandler<Intermediate, Final> handler)
              throws Exception
          {
            thisLatch.countDown();
            return Futures.immediateFuture((Final) okResponse());
          }
        }.times(1)
    );

    emitTime = System.currentTimeMillis();
    emitter.emit(new UnitEvent("test", 2));

    thisLatch.await();
    timeWaited = System.currentTimeMillis() - emitTime;
    Assert.assertTrue(
        String.format("timeWaited[%s] !< %s", timeWaited, timeBetweenEmissions * 2),
        timeWaited < timeBetweenEmissions * 2
    );

    waitForEmission(emitter);
    closeNoFlush(emitter);
    Assert.assertTrue("httpClient.succeeded()", httpClient.succeeded());
  }

  @Test
  public void testFailedEmission() throws Exception
  {
    final UnitEvent event1 = new UnitEvent("test", 1);
    final UnitEvent event2 = new UnitEvent("test", 2);
    emitter = sizeBasedEmitter(1);
    Assert.assertEquals(0, emitter.getBufferedSize());

    httpClient.setGoHandler(
        new GoHandler()
        {
          @Override
          public <Intermediate, Final> ListenableFuture<Final> go(Request request, HttpResponseHandler<Intermediate, Final> handler)
              throws Exception
          {
            final Intermediate obj = handler
                                            .handleResponse(
                                                new DefaultHttpResponse(
                                                    HttpVersion.HTTP_1_1,
                                                    HttpResponseStatus.BAD_REQUEST
                                                )
                                            )
                                            .getObj();
            Assert.assertNotNull(obj);
            return Futures.immediateFuture((Final) obj);
          }
        }.times(1)
    );
    emitter.emit(event1);
    waitForEmission(emitter);
    Assert.assertTrue(httpClient.succeeded());

    // The event should still be queued up, since it failed and will want to be retried.
    Assert.assertEquals(jsonMapper.writeValueAsString(event1).length(), emitter.getBufferedSize());

    httpClient.setGoHandler(
        new GoHandler()
        {
          @Override
          public <Intermediate, Final> ListenableFuture<Final> go(Request request, HttpResponseHandler<Intermediate, Final> handler)
              throws Exception
          {
            Assert.assertEquals(
                jsonMapper.convertValue(
                    ImmutableList.of(event1.toMap(), event2.toMap()), List.class
                ),
                jsonMapper.readValue(request.getContent().toString(Charsets.UTF_8), List.class)
            );

            return Futures.immediateFuture((Final) okResponse());
          }
        }.times(1)
    );

    emitter.emit(event2);
    waitForEmission(emitter);

    // Nothing should be queued up, since everything has succeeded.
    Assert.assertEquals(0, emitter.getBufferedSize());

    closeNoFlush(emitter);
    Assert.assertTrue(httpClient.succeeded());
  }

  @Test
  public void testBasicAuthenticationAndNewlineSeparating() throws Exception
  {
    final List<UnitEvent> events = Arrays.asList(
        new UnitEvent("test", 1),
        new UnitEvent("test", 2)
    );
    emitter = manualFlushEmitterWithBasicAuthenticationAndNewlineSeparating("foo:bar");

    httpClient.setGoHandler(
        new GoHandler()
        {
          @Override
          public <Intermediate, Final> ListenableFuture<Final> go(Request request, HttpResponseHandler<Intermediate, Final> handler) throws Exception
          {
            Assert.assertEquals(new URL(TARGET_URL), request.getUrl());
            Assert.assertEquals(
                ImmutableList.of("application/json"),
                request.getHeaders().get(HttpHeaders.Names.CONTENT_TYPE)
            );
            Assert.assertEquals(
                ImmutableList.of("Basic " + BaseEncoding.base64().encode("foo:bar".getBytes())),
                request.getHeaders().get(HttpHeaders.Names.AUTHORIZATION)
            );
            Assert.assertEquals(
                String.format(
                    "%s\n%s\n",
                    jsonMapper.writeValueAsString(events.get(0)),
                    jsonMapper.writeValueAsString(events.get(1))
                ),
                request.getContent().toString(Charsets.UTF_8)
            );
            Assert.assertTrue(
                "handler is a StatusResponseHandler",
                handler instanceof StatusResponseHandler
            );

            return Futures.immediateFuture((Final) okResponse());
          }
        }.times(1)
    );

    for (UnitEvent event : events) {
      emitter.emit(event);
    }
    emitter.flush();
    waitForEmission(emitter);
    closeNoFlush(emitter);
    Assert.assertTrue(httpClient.succeeded());
  }

  @Test
  public void testBatchSplitting() throws Exception
  {
    final byte[] big = new byte[500 * 1024];
    for(int i = 0; i < big.length ; i++ ) {
      big[i] = 'x';
    }
    final String bigString = new String(big);
    final List<UnitEvent> events = Arrays.asList(
        new UnitEvent(bigString, 1),
        new UnitEvent(bigString, 2),
        new UnitEvent(bigString, 3),
        new UnitEvent(bigString, 4)
    );
    final AtomicInteger counter = new AtomicInteger();
    emitter = manualFlushEmitterWithBatchSizeAndBufferSize(1024 * 1024, 5 * 1024 * 1024);
    Assert.assertEquals(0, emitter.getBufferedSize());

    httpClient.setGoHandler(
        new GoHandler()
        {
          @Override
          public <Intermediate, Final> ListenableFuture<Final> go(Request request, HttpResponseHandler<Intermediate, Final> handler) throws Exception
          {
            Assert.assertEquals(new URL(TARGET_URL), request.getUrl());
            Assert.assertEquals(
                ImmutableList.of("application/json"),
                request.getHeaders().get(HttpHeaders.Names.CONTENT_TYPE)
            );
            Assert.assertEquals(
                String.format(
                    "[%s,%s]\n",
                    jsonMapper.writeValueAsString(events.get(counter.getAndIncrement())),
                    jsonMapper.writeValueAsString(events.get(counter.getAndIncrement()))
                ),
                request.getContent().toString(Charsets.UTF_8)
            );
            Assert.assertTrue(
                "handler is a StatusResponseHandler",
                handler instanceof StatusResponseHandler
            );

            return Futures.immediateFuture((Final) okResponse());
          }
        }.times(3)
    );

    for (UnitEvent event : events) {
      emitter.emit(event);
    }
    Assert.assertEquals(jsonMapper.writeValueAsString(events).length() - events.size() - 1, emitter.getBufferedSize());

    emitter.flush();
    waitForEmission(emitter);
    Assert.assertEquals(0, emitter.getBufferedSize());
    closeNoFlush(emitter);
    Assert.assertTrue(httpClient.succeeded());
  }

  private void closeAndExpectFlush(Emitter emitter) throws IOException
  {
    httpClient.setGoHandler(GoHandlers.passingHandler(okResponse()).times(1));
    emitter.close();
  }

  private void closeNoFlush(Emitter emitter) throws IOException
  {
    emitter.close();
  }

  private void waitForEmission(HttpPostEmitter emitter) throws InterruptedException
  {
    final CountDownLatch latch = new CountDownLatch(1);
    emitter.getExec().execute(
        new Runnable()
        {
          @Override
          public void run()
          {
            latch.countDown();
          }
        }
    );

    if (!latch.await(10, TimeUnit.SECONDS)) {
      Assert.fail("latch await() did not complete in 10 seconds");
    }
  }
}
