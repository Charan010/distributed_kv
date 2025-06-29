package LoadBalancer;

import static spark.Spark.*;
import com.google.gson.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import hashing.*;

class Pinger implements Runnable{

private List<String> nodes = null;
private static int TIMEOUT = 2000;
private ConsistentHashing hashRing = null;

Pinger(List<String> servers, ConsistentHashing HashRing){
    nodes = servers;
    hashRing = HashRing;
}

@Override
public void run(){
    while(true){
        for(String server: new ArrayList<>(nodes)){
            try{
                boolean isWorking = InetAddress.getByName(server).isReachable(TIMEOUT);
                if(!isWorking){
                    this.deleteNodeToHashRing(server);
                }else{
                    this.addNodeToHashRing(server);
                }

            }catch(Exception e){
                System.out.println("[ERROR]:" + e.getMessage());
            }
        }
        try{Thread.sleep(5000);}
        catch(InterruptedException e){
            System.out.println("Daemon thread Error:" + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}

private void addNodeToHashRing(String node){
    hashRing.addNode(new Node(node));
        System.out.println("[INFO] Node " + node + " added to the hash ring :P");
}

private void deleteNodeToHashRing(String node){
    hashRing.removeNode(new Node(node));
    System.out.println("[INFO] Node " + node + " removed from hash ring");

}
}

public class LoadBalancer{

    //for wrapping and sending json req
    static class PutRequest {
        String key;
        String value;
    }

    //set of dummy nodes (servers) to test.
    private static final List<String> nodes = List.of(
            "http://localhost:8081",
            "http://localhost:8082",
            "http://localhost:8083"
    );

    private static final int VIRTUAL_NODES = 5;
    private static ConsistentHashing hashRing;
    private static sha1HashFunction hashFunc;

    public LoadBalancer(){
        hashFunc = new sha1HashFunction();
        hashRing = new ConsistentHashing(hashFunc, VIRTUAL_NODES);
        buildHashRing();
    }  

    private void buildHashRing(){

        System.out.println("Starting Hash ring....");

        for(String server: nodes){
            hashRing.addNode(new Node(server));
            System.out.println("↪Added Node:"+ server);
          }
    }

    private String handlePut(String body, Gson gson) {
        PutRequest req = gson.fromJson(body, PutRequest.class);
    
        if (req.key == null || req.value == null) {
            return "Error: Missing key or value in request body";
        }

        Node node = hashRing.getNode(req.key);

        try {
            
            // warning: constructer for URL is depracted but chalega for now :P
            URL url = new URL(node.getId() + "/put");

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            JsonObject payload = new JsonObject();
            payload.addProperty("key", req.key);
            payload.addProperty("value", req.value);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();

            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String response = new String(is.readAllBytes());

            return String.format(
                "[FORWARD] Sent to %s\n↪ Status: %d\n↪ Response: %s",
                node.getId(), code, response
            );

        }catch (IOException e) {
            return "Error forwarding request to node: " + e.getMessage();
        }
    }

    public String handleGet(String key) {
        if (key == null) return "Error:key is null or missing";

        Node node = hashRing.getNode(key);

        try {
            URL url = new URL(node.getId() + "/get?key=" + URLEncoder.encode(key, "UTF-8"));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            String response = new String(conn.getInputStream().readAllBytes());

            return String.format("[GET] ➜ %s\n↪ Status: %d\n↪ Response: %s",node.getId(), code, response);

        } catch (IOException e) {
            return "Error GET req forwarding " + e.getMessage();
        }
    }

    public String handleDelete(String key) {
        if (key == null) return "Error: key is null or missing";
        
        Node node = hashRing.getNode(key);
        
        try {
            URL url = new URL(node.getId() + "/delete?key=" + URLEncoder.encode(key, "UTF-8"));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");

            int respcode = conn.getResponseCode();
            String response = new String(conn.getInputStream().readAllBytes());

            return String.format("[DELETE] ➜ %s\n↪ Status: %d\n↪ Response: %s",
                             node.getId(), respcode, response);

        } catch (IOException e) {
            return "Error forwarding DELETE to node: " + e.getMessage();
        }
    }

    public static void main(String[] args){
        port(8080);
        Gson gson = new Gson();

        LoadBalancer lb = new LoadBalancer();

        Pinger pinger = new Pinger(LoadBalancer.nodes, LoadBalancer.hashRing);

        //Runs in background to ping all servers and removes if inactive.
        Thread daemonPinger = new Thread(pinger);
        daemonPinger.setDaemon(true);

        System.out.println("[INFO] Daemon Pinger is starting :P ....");
        daemonPinger.start();

        post("/put", (req, res) -> {
            res.type("application/json");
            return lb.handlePut(req.body(), gson);
        });

        get("/get", (req, res) -> {
            res.type("application/json");
            return lb.handleGet(req.queryParams("key"));
        });

        delete("/delete", (req, res) -> {
            res.type("application/json");
            return lb.handleDelete(req.queryParams("key"));
        });

    }
}
