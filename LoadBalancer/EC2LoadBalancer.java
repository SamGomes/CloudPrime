import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.ec2.model.Instance;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class EC2LoadBalancer {

	private static HashMap<String,Instance> instances;
	private static int next = 0;
    private static int TIME_TO_REFRESH_INSTANCES = 5000;
    private static Timer timer = new Timer();
    private static String LoadBalancerIp;
    private static final BigInteger THRESHOLD = new BigInteger("2300"); //TODO: set a meaningful value
    private static ArrayList<BigInteger> pendingRequests = new ArrayList<>();
    private static final String INSTANCE_LOAD_TABLE_NAME = "MSS Instance Load";
 
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        LoadBalancerIp = InetAddress.getLocalHost().getHostAddress();
        server.createContext("/f.html", new MyHandler());
        server.setExecutor(null); // creates a default executor
        createInstanceList();
        server.start();
        startTimer();
    }

    public static void createInstanceList(){
    	
    	try{
        	EC2LBGeneralOperations.init();
            DynamoDBGeneralOperations.init();
            EC2LBGeneralOperations.addLoadBalancerToExceptionList(LoadBalancerIp);
        	//instances = EC2LBGeneralOperations.getInstances();
            instances = EC2LBGeneralOperations.getRunningInstancesArray();
        	next = 0;
    	}catch(Exception e){

    	}
    }
 
    public static HashMap queryToMap(String query){
 	    HashMap result = new HashMap();
 	    String[] params = query.split("&");
 	    for (int i=0; i< params.length;i++) {
 	        String pair[] = params[i].split("=");
 	        if (pair.length>1) {
 	            result.put(pair[0], pair[1]);
 	        }else{
 	            result.put(pair[0], "");
 	        }
 	    }
 	    return result;
 	}
    
    static class MyHandler implements HttpHandler {
        public void handle(final HttpExchange exchange) throws IOException {

        new Thread(new Runnable(){

			//@Override
			public void run() {
				Headers responseHeaders = exchange.getResponseHeaders();
				responseHeaders.set("Content-Type", "text/html");
				HashMap map = queryToMap(exchange.getRequestURI().getQuery());

			  try{
                  BigInteger numberToBeFactored = new BigInteger(map.get("n").toString());
                  Instance instance = getBestMachineIp(numberToBeFactored);
                  if (instance == null){
                      System.out.println("Could not find any instance to serve the request");
                  }else{
                    System.out.println(instance.getInstanceId());
                  }
                  String url = "http://"+instance.getPublicIpAddress()+":8000/f.html?n="+numberToBeFactored;

                  //TODO: method that update the instance load
                  //Map<String, AttributeValue> instanceLoad = DynamoDBWebServerGeneralOperations.getInstanceTuple(INSTANCE_LOAD_TABLE_NAME,instance.getInstanceId());
                  //int currentLoad = Integer.parseInt(instanceLoad.get(instance.getInstanceId()).getS());
                  //DynamoDBWebServerGeneralOperations.updateInstanceLoad(INSTANCE_LOAD_TABLE_NAME, currentLoad+0);

                  HttpClient client = HttpClientBuilder.create().build();
                  HttpGet request = new HttpGet(url);

                  HttpResponse response = client.execute(request);
                  System.out.println("Response Code : "
                          + response.getStatusLine().getStatusCode());

                  BufferedReader rd = new BufferedReader(
                          new InputStreamReader(response.getEntity().getContent(),StandardCharsets.UTF_8));

                  StringBuilder result = new StringBuilder();
                  String line;
                  while ((line = rd.readLine()) != null) {
                      result.append(line);
                  }

                  exchange.sendResponseHeaders(200, result.length());
                  OutputStream os = exchange.getResponseBody();
                  os.write(result.toString().getBytes());
                  os.close();

              }catch(Exception e){}
            }
        }).start();
        }
    }

    public static void startTimer(){
        // scheduling the task at interval

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateRunningInstances();
            }
        }, TIME_TO_REFRESH_INSTANCES, TIME_TO_REFRESH_INSTANCES);
    }

    public static void updateRunningInstances(){
        instances = null;
        instances = EC2LBGeneralOperations.getRunningInstancesArray();
        System.out.println("Running instances: "+instances.size());
    }

    public static Instance getBestMachineIp(BigInteger costEstimation){
        Instance result = null;
        updateRunningInstances(); //update running instances

        BigInteger response = DynamoDBGeneralOperations.estimateCostScan(costEstimation); //This function returns the result of the scan request
        //BigInteger response = DynamoDBGeneralOperations.estimateCost(costEstimation); //TODO: return the result with a query request
        System.out.println("Estimated cost "+response.toString());

        //TODO: get the current load of instances from MSS
/*        HashMap<String, Integer> instanceLoad = getRunningInstancesLoad(instances);

        for (Map.Entry<String,Integer> entry: instanceLoad.entrySet()){
            //instance can process the request
            if (BigInteger.valueOf(entry.getValue()).add(costEstimation).compareTo(THRESHOLD) == -1){
                //TODO: update instance load
                return instances.get(entry.getKey()); // return instance
            } else {
                pendingRequests.add()
                //continue to check if other instances can process the request
            }
        }*/

        for (Map.Entry<String,Instance> entry: instances.entrySet()){
            Map<String, AttributeValue> instanceLoad = null;
            try {
                instanceLoad = DynamoDBGeneralOperations.getInstanceTuple(INSTANCE_LOAD_TABLE_NAME,entry.getValue().getInstanceId());
                //int currentLoad = Integer.parseInt(instanceLoad.get(entry.getKey()).getS());
                BigInteger currentLoad = new BigInteger(instanceLoad.get(entry.getKey()).getS());

                if(currentLoad.add(costEstimation).compareTo(THRESHOLD) == -1){
                    return entry.getValue();
                }else{
                    //pendingRequests.add(); Add to pending list and try later
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
        // if result = "none" -> put the request on hold
        // or launch another instance (?)
        return result;
    }
}
