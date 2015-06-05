// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.grid.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.openqa.grid.common.RegistrationRequest.APP;
import static org.openqa.grid.common.RegistrationRequest.CLEAN_UP_CYCLE;
import static org.openqa.grid.common.RegistrationRequest.ID;
import static org.openqa.grid.common.RegistrationRequest.TIME_OUT;

import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.listeners.TimeoutListener;
import org.openqa.grid.internal.mock.GridHelper;
import org.openqa.grid.web.servlet.handler.RequestHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SessionTimesOutTest {

  private static RegistrationRequest req = new RegistrationRequest();
  private static Map<String, Object> app1 = new HashMap<String, Object>();

  // create a request for a proxy that times out after 0.5 sec.
  @BeforeClass
  public static void setup() {

    app1.put(APP, "app1");
    req.addDesiredCapability(app1);

    Map<String, Object> config = new HashMap<String, Object>();
    // a test is timed out is inactive for more than 0.5 sec.
    config.put(TIME_OUT, 50);

    // every 0.5 sec, the proxy check is something has timed out.
    config.put(CLEAN_UP_CYCLE, 400);

    config.put(ID, "abc");
    config.put("host", "localhost");

    req.setConfiguration(config);
  }

  class MyRemoteProxyTimeout extends DetachedRemoteProxy implements TimeoutListener {

    public MyRemoteProxyTimeout(RegistrationRequest request, Registry registry) {
      super(request, registry);
    }

    public void beforeRelease(TestSession session) {
    }
  }

  /**
   * check that the proxy is freed after it times out.
   */
  @Test(timeout = 3000)
  public void testTimeout() throws InterruptedException {

    Registry registry = Registry.newInstance();
    RemoteProxy p1 = new MyRemoteProxyTimeout(req, registry);
    p1.setupTimeoutListener();

    try {
      registry.add(p1);
      RequestHandler newSessionRequest = GridHelper.createNewSessionHandler(registry, app1);

      newSessionRequest.process();
      TestSession session = newSessionRequest.getSession();
      // wait for a timeout
      Thread.sleep(500);

      RequestHandler newSessionRequest2 = GridHelper.createNewSessionHandler(registry, app1);
      newSessionRequest2.process();
      TestSession session2 = newSessionRequest2.getSession();
      assertNotNull(session2);
      assertNotSame(session, session2);
    } finally {
      registry.stop();
    }
  }

  private static boolean timeoutDone = false;

  class MyRemoteProxyTimeoutSlow extends DetachedRemoteProxy implements TimeoutListener {

    public MyRemoteProxyTimeoutSlow(RegistrationRequest request, Registry registry) {
      super(request, registry);
    }

    public void beforeRelease(TestSession session) {
      try {
        Thread.sleep(1000);
        timeoutDone = true;
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  @Test(timeout = 5000)
  public void testTimeoutSlow() throws InterruptedException {
    Registry registry = Registry.newInstance();
    RemoteProxy p1 = new MyRemoteProxyTimeoutSlow(req, registry);
    p1.setupTimeoutListener();

    try {
      registry.add(p1);

      RequestHandler newSessionRequest = GridHelper.createNewSessionHandler(registry, app1);
      newSessionRequest.process();
      TestSession session = newSessionRequest.getSession();
      // timeout cleanup will start
      Thread.sleep(500);
      // but the session finishes before the timeout cleanup finishes
      registry.terminate(session, SessionTerminationReason.CLIENT_STOPPED_SESSION);

      // wait to have the slow time out process finished
      int i = 0;
      while (!timeoutDone) {
        if (i >= 4) {
          throw new RuntimeException("should be true");
        }
        Thread.sleep(250);
      }
      assertTrue(timeoutDone);

      RequestHandler newSessionRequest2 = GridHelper.createNewSessionHandler(registry, app1);
      newSessionRequest2.process();
      TestSession session2 = newSessionRequest2.getSession();
      assertNotNull(session2);
      assertTrue(session.equals(session));
      assertFalse(session2.equals(session));

    } finally {
      registry.stop();
    }
  }

  class MyBuggyRemoteProxyTimeout extends DetachedRemoteProxy implements TimeoutListener {

    public MyBuggyRemoteProxyTimeout(RegistrationRequest request, Registry registry) {
      super(request, registry);
    }

    public void beforeRelease(TestSession session) {
      throw new NullPointerException();
    }
  }

  // a proxy throwing an exception will end up not releasing the resources.
  @Test(timeout = 5000)
  public void testTimeoutBug() throws InterruptedException {
    final Registry registry = Registry.newInstance();
    RemoteProxy p1 = new MyBuggyRemoteProxyTimeout(req, registry);
    p1.setupTimeoutListener();

    try {
      registry.add(p1);

      RequestHandler newSessionRequest = GridHelper.createNewSessionHandler(registry, app1);
      newSessionRequest.process();

      final RequestHandler newSessionRequest2 =
          GridHelper.createNewSessionHandler(registry, app1);
      new Thread(new Runnable() {  // Thread safety reviewed
        public void run() {
          // the request should never be processed because the
          // resource is not released by the buggy proxy
          newSessionRequest2.process();
        }
      }).start();

      // wait for a timeout
      Thread.sleep(500);
      // the request has not been processed yet.
      assertNull(newSessionRequest2.getServerSession());
    } finally {
      registry.stop();
    }
  }

  class MyStupidConfig extends DetachedRemoteProxy implements TimeoutListener {

    public MyStupidConfig(RegistrationRequest request, Registry registry) {
      super(request, registry);
    }

    public void beforeRelease(TestSession session) {
      session.put("FLAG", true);
      session.put("MustSupportNullValue", null);
      session.put(null, "MustSupportNullKey");
    }
  }

  @Test(timeout = 10000)
  public void stupidConfig() throws InterruptedException {
    Object[][] configs = new Object[][]{
        // correct config, just to check something happens
        {5, 5},
        // and invalid ones
        {-1, 5}, {5, -1}, {-1, -1}, {0, 0}};
    java.util.List<Registry> registryList = new ArrayList<Registry>();
    try {
      for (Object[] c : configs) {
        int timeout = (Integer) c[0];
        int cycle = (Integer) c[1];
        Registry registry = Registry.newInstance();
        registryList.add(registry);

        RegistrationRequest req = new RegistrationRequest();
        Map<String, Object> app1 = new HashMap<String, Object>();
        app1.put(APP, "app1");
        req.addDesiredCapability(app1);
        Map<String, Object> config = new HashMap<String, Object>();

        config.put(TIME_OUT, timeout);
        config.put(CLEAN_UP_CYCLE, cycle);
        config.put(ID, "abc");
        config.put("host", "localhost");

        req.setConfiguration(config);

        final MyStupidConfig proxy = new MyStupidConfig(req, registry);
        proxy.setupTimeoutListener();
        registry.add(proxy);
        RequestHandler newSessionRequest = GridHelper.createNewSessionHandler(registry, app1);
        newSessionRequest.process();
        TestSession session = newSessionRequest.getSession();
        // wait -> timed out and released.
        Thread.sleep(500);
        boolean shouldTimeout = timeout > 0 && cycle > 0;

        if (shouldTimeout) {
          assertEquals(session.get("FLAG"), true);
          assertNull(session.getSlot().getSession());
        } else {
          assertNull(session.get("FLAG"));
          assertNotNull(session.getSlot().getSession());
        }
      }
    } finally {
      for (Registry registry : registryList) {
        registry.stop();
      }
    }

  }

}
