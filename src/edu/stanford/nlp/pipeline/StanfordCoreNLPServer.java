package edu.stanford.nlp.pipeline;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.util.MetaClass;
import edu.stanford.nlp.util.Pair;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

import static edu.stanford.nlp.util.logging.Redwood.Util.*;

/**
 * This class creates a server that runs a new Java annotator in each thread.
 *
 */
public class StanfordCoreNLPServer implements Runnable {
  protected HttpServer server;
  protected int serverPort = 9000;

  public static int HTTP_OK = 200;
  public static int HTTP_BAD_INPUT = 400;
  public static int HTTP_ERR = 500;
  public final Properties defaultProps;


  public StanfordCoreNLPServer() {
    defaultProps = new Properties();
//        defaultProps.setProperty("annotators", "tokenize, ssplit, pos, lemma, ner, parse, dcoref");
    defaultProps.setProperty("annotators", "tokenize,ssplit,pos,lemma,depparse");
    defaultProps.setProperty("inputFormat", "text");
    defaultProps.setProperty("outputFormat", "json");
  }


  protected static Map<String, String> parseJSONMap(String json) {
    Map<String, String> map = new HashMap<>();
    String escaped = json
        .replace("\\\\", "__ESCAPED_SLASH__")
        .replace("\\\b", "__ESCAPED_B__")
        .replace("\\\f", "__ESCAPED_F__")
        .replace("\\\n", "__ESCAPED_N__")
        .replace("\\\r", "__ESCAPED_R__")
        .replace("\\\t", "__ESCAPED_T__")
        .replace("\\\"", "__ESCAPED_QUOTE__").trim();
    String[] mapEntries = escaped.substring(1, escaped.length() - 1).split(",");
    for (String mapEntry : mapEntries) {
      String[] fields = mapEntry.split(":");
      String key = fields[0].trim()
          .replace("__ESCAPED_SLASH__", "\\\\")
          .replace("__ESCAPED_B__", "\\\b")
          .replace("__ESCAPED_F__", "\\\f")
          .replace("__ESCAPED_N__", "\\\n")
          .replace("__ESCAPED_R__", "\\\r")
          .replace("__ESCAPED_T__", "\\\t")
          .replace("__ESCAPED_QUOTE__", "\\\"")
          .replaceAll("^\"", "")
          .replaceAll("\"$", "")
          .trim();
      String value = fields[1].trim()
          .replace("__ESCAPED_SLASH__", "\\\\")
          .replace("__ESCAPED_B__", "\\\b")
          .replace("__ESCAPED_F__", "\\\f")
          .replace("__ESCAPED_N__", "\\\n")
          .replace("__ESCAPED_R__", "\\\r")
          .replace("__ESCAPED_T__", "\\\t")
          .replace("__ESCAPED_QUOTE__", "\\\"")
          .replaceAll("^\"", "")
          .replaceAll("\"$", "").trim();
      map.put(key, value);
    }
    return map;
  }

  /**
   *
   */
  protected static class PingHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      // Return a simple text message that says pong.
      httpExchange.getResponseHeaders().set("Content-Type", "text/plain");
      String response = "pong\n";
      httpExchange.sendResponseHeaders(HTTP_OK, response.length());
      httpExchange.getResponseBody().write(response.getBytes());
      httpExchange.close();
    }
  }

  /**
   * The main handler for taking an annotation request, and annotating it.
   */
  protected static class SimpleAnnotateHandler implements HttpHandler {
    /**
     * The default properties to use in the absence of anything sent by the client.
     */
    public final Properties defaultProps;
    /**
     * To prevent grossly wasteful over-creation of pipeline objects, cache the last
     * few we created, until the garbage collector decides we can kill them.
     */
    private final WeakHashMap<Properties, StanfordCoreNLP> pipelineCache = new WeakHashMap<>();

    /**
     * Create a handler for accepting annotation requests.
     * @param props The properties file to use as the default if none were sent by the client.
     */
    public SimpleAnnotateHandler(Properties props) {
      defaultProps = props;
    }

    /**
     * Create (or retrieve) a StanfordCoreNLP object corresponding to these properties.
     * @param props The properties to create the object with.
     * @return A pipeline parameterized by these properties.
     */
    private StanfordCoreNLP mkStanfordCoreNLP(Properties props) {
      StanfordCoreNLP impl;
      synchronized (pipelineCache) {
        impl = pipelineCache.get(props);
        if (impl == null) {
          impl = new StanfordCoreNLP(props);
          pipelineCache.put(props, impl);
        }
      }
      return impl;
    }

    public String getContentType(Properties props, StanfordCoreNLP.OutputFormat of) {
      switch(of) {
        case JSON:
          return "text/json";
        case TEXT:
        case CONLL:
          return "text/plain";
        case XML:
          return "text/xml";
        case SERIALIZED:
          String outputSerializerName = props.getProperty("outputSerializer");
          if (outputSerializerName != null &&
              outputSerializerName.equals(ProtobufAnnotationSerializer.class.getName())) {
            return "application/x-protobuf";
          }
        default:
          return "application/octet-stream";
      }
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      // Get sentence.
      Properties props;
      Annotation ann;
      StanfordCoreNLP.OutputFormat of;
      try {
        props = getProperties(httpExchange);
        ann = getDocument(props, httpExchange);
        of = StanfordCoreNLP.OutputFormat.valueOf(props.getProperty("outputFormat").toUpperCase());
      } catch (IOException | ClassNotFoundException e) {
        // Return error message.
        e.printStackTrace();
        String response = e.getMessage();
        httpExchange.getResponseHeaders().add("Content-Type", "text/plain");
        httpExchange.sendResponseHeaders(HTTP_BAD_INPUT, response.length());
        httpExchange.getResponseBody().write(response.getBytes());
        httpExchange.close();
        return;
      }

      try {
        // Annoatate
        StanfordCoreNLP pipeline = mkStanfordCoreNLP(props);
        pipeline.annotate(ann);

        // Get output
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        StanfordCoreNLP.createOutputter(props, AnnotationOutputter.getOptions(pipeline)).accept(ann, os);
        os.close();
        byte[] response = os.toByteArray();

        httpExchange.getResponseHeaders().add("Content-Type", getContentType(props, of));
        httpExchange.getResponseHeaders().add("Content-Length", Integer.toString(response.length));
        httpExchange.sendResponseHeaders(HTTP_OK, response.length);
        httpExchange.getResponseBody().write(response);
        httpExchange.close();
      } catch (Exception e) {
        // Return error message.
        e.printStackTrace();
        String response = e.getMessage();
        httpExchange.getResponseHeaders().add("Content-Type", "text/plain");
        httpExchange.sendResponseHeaders(HTTP_ERR, response.length());
        httpExchange.getResponseBody().write(response.getBytes());
        httpExchange.close();
      }
    }

    private Properties getProperties(HttpExchange httpExchange) throws UnsupportedEncodingException {
      String query = URLDecoder.decode(httpExchange.getRequestURI().getRawQuery(), "UTF-8");
      String[] queryFields = query.split("&");
      Map<String, String> urlParams = new HashMap<>();
      for (String queryField : queryFields) {
        String[] keyValue = queryField.split("=");
        urlParams.put(keyValue[0], keyValue[1]);
      }

      Properties props = new Properties();
      defaultProps.entrySet().stream()
          .forEach(entry -> props.setProperty(entry.getKey().toString(), entry.getValue().toString()));
      if (urlParams.containsKey("properties")) {
        // Parse properties
        parseJSONMap(urlParams.get("properties")).entrySet()
            .forEach(entry -> props.setProperty(entry.getKey(), entry.getValue()));
      }

      return props;
    }

    private Annotation getDocument(Properties props, HttpExchange httpExchange) throws IOException, ClassNotFoundException {
      String inputFormat = props.getProperty("inputFormat");
      switch (inputFormat) {
        case "text":
          return new Annotation(IOUtils.slurpReader(new InputStreamReader(httpExchange.getRequestBody())));
        case "serialized":
          String inputSerializerName = props.getProperty("inputSerializer");
          AnnotationSerializer serializer = MetaClass.create(inputSerializerName).createInstance();
          Pair<Annotation, InputStream> pair = serializer.read(httpExchange.getRequestBody());
          return pair.first;
        default:
          throw new IOException("Could not parse input format: " + inputFormat);
      }
    }
  }

  @Override
  public void run() {
    try {

      server = HttpServer.create(new InetSocketAddress(serverPort), 0); // 0 is the default 'backlog'
      server.createContext("/ping", new PingHandler());
      server.createContext("/", new SimpleAnnotateHandler(defaultProps));
//            server.createContext("/protobuf", new PingHandler());
      server.start();
      log("StanfordCoreNLPServer listening at " + server.getAddress());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static void main(String[] args) {
    StanfordCoreNLPServer server = new StanfordCoreNLPServer();
    if (args.length > 0) {
      server.serverPort = Integer.parseInt(args[0]);
    }
    server.run();
  }
}