/*
 * Copyright 2011-2012 the original author or authors.
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

package org.vertx.mods.web;

import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Future;
import org.vertx.java.core.Handler;
import org.vertx.java.core.VoidHandler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.RouteMatcher;
import org.vertx.java.core.http.ServerWebSocket;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.shareddata.ConcurrentSharedMap;
import org.vertx.java.core.sockjs.SockJSServer;
import org.vertx.java.core.sockjs.SockJSSocket;
import org.vertx.java.core.streams.Pump;


import java.io.File;
import java.util.Set;
import java.util.HashSet;


/**
 * A simple web server base module that can serve static files, provides an
 * extension point for a configurable RouteMatcher, and also can bridge 
 * event bus messages to/from client side JavaScript and the server side
 * event bus.
 *
 * Please see the modules manual for full description of what configuration
 * parameters it takes.
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 * @author pidster
 */
public abstract class WebServerBase extends BusModBase {

  public static final int DEFAULT_PORT = 80;

  public static final String DEFAULT_ADDRESS = "0.0.0.0";

  public static final String DEFAULT_WEB_ROOT = "web";

  public static final String DEFAULT_INDEX_PAGE = "index.html";

  public static final String DEFAULT_AUTH_ADDRESS = "vertx.basicauthmanager.authorise";

  public static final long DEFAULT_AUTH_TIMEOUT = 5 * 60 * 1000;

  private final Set<String> webSocketPaths = new HashSet();;
  
  @Override
  public void start(final Future<Void> result) {
    start();

    HttpServer server = vertx.createHttpServer();
    final EventBus eb = vertx.eventBus();
    
    if (getOptionalBooleanConfig("ssl", false)) {
      server.setSSL(true).setKeyStorePassword(getOptionalStringConfig("key_store_password", "wibble"))
                         .setKeyStorePath(getOptionalStringConfig("key_store_path", "server-keystore.jks"));
    }

    if (getOptionalBooleanConfig("route_matcher", false)) {
        server.requestHandler(routeMatcher());
    }
    else if (getOptionalBooleanConfig("static_files", true)) {
        server = server.requestHandler(staticHandler());
        
        //read the websocket path elements passed in config (ws_path_01, ws_path_02, ...)
        // and populate local 'webSocketPaths' set:
        boolean readWebSocketConfig = true;
        int i = 1;
        while (readWebSocketConfig) {
            String wsPathNum = new String();
            try {
                wsPathNum = "ws_path_" + String.format("%02d", i);
                System.out.println("processing ws path num: " + wsPathNum);
                String wsPath = getMandatoryStringConfig(wsPathNum);
                System.out.println("got ws path: " + wsPath);
                webSocketPaths.add(wsPath);
                i++;
            } catch (IllegalArgumentException ex) {
                //no ws_path_xx:
                System.out.println("Couldn't find config element for ws path num: " + wsPathNum + ", exiting ws config loop");
                readWebSocketConfig = false;
            }
        
        }
        
        //define a webSocketHandler to handle all configured websockets for the module (see above for config):
        server.websocketHandler(new Handler<ServerWebSocket>() {
            public void handle(final ServerWebSocket ws) {
                //debug:
                System.out.println("Webserver handling new websocket connection, remoteAddress: " + ws.remoteAddress().toString() + ", localAddress: " + ws.localAddress().toString());
                
                /*
                This for loop iterates through all web socket paths specified in the module config file (ws_path_01, ws_path_02, ...), checking for matches
                  against ws.path value (from ServerWebSocket).  If there is a match, the textHandlerID value for this websocket is added to a SharedData
                  set with the name of the websocket path (eg. weather_obs_stream, or whatever).  This allows EventBus publish to be called to publish a
                  message to each textHandlerID of an active websocket client connection.  
                If there is not a match, reject the websocket.
                */
                boolean matched = false;
                for (final String wsPath: webSocketPaths) {
                    //if (ws.path().matches("/" + wsPath + ".*")) {
                    if (ws.path().equals("/" + wsPath)) {
                        //System.out.println("matched! wsPath: " + wsPath + ", ws.path: " + ws.path());
                        matched = true;
                        
                        //get or create shareddata set named using websocket path, add the textHandlerID for publishing:
                        final Set<String> set = vertx.sharedData().getSet(wsPath);
                        set.add(ws.textHandlerID());
                        //System.out.println("added to set: " + wsPath + " textHandlerID: " + ws.textHandlerID());
                        
                        //debug:
                        for (String id: set.toArray(new String[0])) { System.out.println("set: " + wsPath + " contains textHandlerID: " + id);}
                        
                        /*
                        dataHandler: echo incoming message to textHandlerIDs of all active client websocket connections
                        */
                        ws.dataHandler(new Handler<Buffer>() {
                            public void handle(Buffer data) {
                                System.out.println("> " + data.toString());
                                //ws.write(data.toString());
                                //ws.writeTextFrame(data.toString());
                                
                                //publish the incoming message to all websocket textHandlerIDs connected to this socket
                                for (String id: set.toArray(new String[0])) {
                                    eb.publish(id, data.toString());
                                }
                            }
                        });
                        /*
                        closeHandler: handle a closed websocket from client: remove from 'webSocketPaths' set:
                        */
                        ws.closeHandler(new VoidHandler() {
                            public void handle() {
                                try {
                                    if (set.contains(ws.textHandlerID())) {
                                        set.remove(ws.textHandlerID());
                                        //System.out.println("removed from: " + wsPath + "textHandlerID: " + ws.textHandlerID());
                                    }
                                    
                                } catch (Exception ex) {
                                    System.out.println("failed to remove from: " + wsPath + "textHandlerID: " + ws.textHandlerID());
                                }
                            }
                        });
                        /*
                        exceptionHandler: just print an error message for now...
                        */
                        ws.exceptionHandler(new Handler<Throwable>() {
                            public void handle(Throwable t) {
                                System.out.println("Oops, something went wrong processing websocket: " + wsPath + ", error: " + t.toString() + ", message: " + t.getMessage());
                            }
                        });
                        
                    } else {
                        //System.out.println("not a match :( wsPath: " + wsPath + ", ws.path: " + ws.path());
                    }
                }
                //if the 
                if (!matched) ws.reject();
                
            }      
        });
    }

    // Must always bridge AFTER setting request handlers
    boolean bridge = getOptionalBooleanConfig("bridge", false);
    if (bridge) {
      System.out.println("Deploying EventBus bridge and associated stuff...");
      SockJSServer sjsServer = vertx.createSockJSServer(server);
      JsonArray inboundPermitted = getOptionalArrayConfig("inbound_permitted", new JsonArray());
      JsonArray outboundPermitted = getOptionalArrayConfig("outbound_permitted", new JsonArray());

      sjsServer.bridge(getOptionalObjectConfig("sjs_config", new JsonObject().putString("prefix", "/eventbus")),
          inboundPermitted, outboundPermitted,
          getOptionalLongConfig("auth_timeout", DEFAULT_AUTH_TIMEOUT),
          getOptionalStringConfig("auth_address", DEFAULT_AUTH_ADDRESS));
        
        
        //comments here:
        for (int i = 1; i < 11; i++) {
            final String appNum = "sjs_app_" + String.format("%02d", i);
            System.out.println("processing appNum: " + appNum);
            try {
                String appNumPath = getMandatoryStringConfig(appNum);
                System.out.println("got appNumPath: " + appNumPath);
                sjsServer.installApp(new JsonObject().putString("prefix", appNumPath), new Handler<SockJSSocket>() {
                    public void handle(final SockJSSocket sock) {
                        System.out.println("Open: " + sock);    
                        
                        //put the EventBus writeHandlerID in shared memory:
                        ConcurrentSharedMap<String, String> map = vertx.sharedData().getMap("app.paths");
                        map.put(appNum, sock.writeHandlerID());
                        System.out.println("app.paths contents: appNum: " + appNum + ", writeHandlerID: " + map.get(appNum));
                        
                        
                        //is this necessary or will any data sent to the writeHandlerID automatically be written to this socket.
                        //Pump.createPump(sock, sock).start();
                        
                        //use instead?
                        sock.dataHandler(new Handler<Buffer>() {
                            public void handle(Buffer buffer) {
                                System.out.println("> " + buffer.toString());
                                sock.write(buffer);
                                //sock.write(new Buffer("This is a test"));
                            }
                        });
                        
                        
                        //handle some events
                        sock.endHandler(new VoidHandler() {
                            public void handle() {
                                System.out.println("SockJSSocket appNum: " + appNum + " writeHandler: " + sock.writeHandlerID() + " closed");
                            }
                        });
                        
                        sock.exceptionHandler(new Handler<Throwable>() {
                            public void handle(Throwable t) {
                                //log.info("Oops, something went wrong", t);
                                System.out.println("Oops, something went wrong: " + t.toString() + ", message: " + t.getMessage());
                            }
                        });
                    }
                
                
                });
                System.out.println("SockJSSocket appNum: " + appNum + " with path: " + appNumPath + " installed");
                
            } catch (IllegalArgumentException ex) {
                //no sjs_app_xx
                System.out.println("Couldn't find config element for appNum: " + appNum + ", exiting installation loop");
                break;
            }
        }
        
        
    } 

    server.listen(getOptionalIntConfig("port", DEFAULT_PORT), getOptionalStringConfig("host", DEFAULT_ADDRESS), new AsyncResultHandler<HttpServer>() {
      @Override
      public void handle(AsyncResult<HttpServer> ar) {
        if (!ar.succeeded()) {
          result.setFailure(ar.cause());
        } else {
          result.setResult(null);
        }
      }
    });
  }

  /**
   * @return RouteMatcher
   */
  protected abstract RouteMatcher routeMatcher();
  //protected RouteMatcher routeMatcher() =  new RouteMatcher();
  //      routeMatcher.get
  //      routeMatcher.noMatch(staticHandler())
  
  
  /**
   * @return Handler for serving static files
   */
  protected Handler<HttpServerRequest> staticHandler() {
    String webRoot = getOptionalStringConfig("web_root", DEFAULT_WEB_ROOT);
    String indexPage = getOptionalStringConfig("index_page", DEFAULT_INDEX_PAGE);
    String webRootPrefix = webRoot + File.separator;
    JsonObject urlMappings = getOptionalObjectConfig("urlMappings", new JsonObject());
    boolean gzipFiles = getOptionalBooleanConfig("gzip_files", false);
    boolean caching = getOptionalBooleanConfig("caching", false);

    return new StaticFileHandler(vertx, webRootPrefix, indexPage, gzipFiles, caching, urlMappings);
  }

}
