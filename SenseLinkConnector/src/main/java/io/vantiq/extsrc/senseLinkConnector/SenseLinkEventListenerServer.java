/**
 * This class acts as an local HTTP server waiting for SenseLink Platform to push event message via HTTP POST
 */
package io.vantiq.extsrc.senseLinkConnector;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import javax.smartcardio.ResponseAPDU;

import com.sun.net.httpserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;
import org.json.*;

public class SenseLinkEventListenerServer {
    final Logger log;
    private SenseLinkConnectorCore source;
    private HttpServer server;

    public SenseLinkEventListenerServer(SenseLinkConnectorCore source, String server_base_url, int port, String server_context) {
        this.source = source;
        log = LoggerFactory.getLogger(this.getClass().getCanonicalName() + '#' + source.getSourceName());

        try {
            server = HttpServer.create(new InetSocketAddress(server_base_url, port), 0);
            ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor)Executors.newFixedThreadPool(10);

            if (!server_context.startsWith("/")) {
                server_context = "/" + server_context;
            }

            server.createContext(server_context, new MessageServerHandler());
            server.setExecutor(threadPoolExecutor);
            server.start();
            log.info("Event Listening Server started at http://" + server_base_url + ":" + port + server_context);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
    }

    public void stop() {
        server.stop(0);
    }

    private class MessageServerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            String requestParamValue = null;
            if ("POST".equals(httpExchange.getRequestMethod())) {
                requestParamValue = handlePostRequest(httpExchange);
            }

            handleResponse(httpExchange, requestParamValue);
        }

        private String handlePostRequest(HttpExchange httpExchange) throws IOException {
            StringBuilder sb = new StringBuilder();
            String line = null;

            BufferedReader bufReader = new BufferedReader(new InputStreamReader(httpExchange.getRequestBody(), StandardCharsets.UTF_8));
            while ((line = bufReader.readLine()) != null)
                sb.append(line);

            log.info(sb.toString());

            // CKK: Send message to VANTIQ
            JSONObject eventJson = new JSONObject(sb.toString());
            source.sendBackEvent(eventJson.toMap());
            return "{}";
        }

        private void handleResponse(HttpExchange httpExchange, String requestParamValue) throws IOException {
            OutputStream outputStream = httpExchange.getResponseBody();
            StringBuilder responseBuilder = new StringBuilder();

            if (requestParamValue != null) {
                responseBuilder.append(requestParamValue);
            }
            else // will be null if not HTTP POST
            {
                responseBuilder.append("{ \"Error\" : \"Method not allowed!\"}");
            }

            String jsonReponse = StringEscapeUtils.escapeJson(responseBuilder.toString());
            httpExchange.getResponseHeaders().set("Content-Type", "application/json");
            httpExchange.sendResponseHeaders((requestParamValue !=null ? 200 : 501), jsonReponse.length());
            outputStream.write(jsonReponse.getBytes());
            outputStream.flush();
            outputStream.close();
            
        }

    }
}
