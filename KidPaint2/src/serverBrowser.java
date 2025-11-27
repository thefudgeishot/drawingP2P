import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Border;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.xml.crypto.Data;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;

public class serverBrowser{

    @FXML
    FlowPane serverList;

    @FXML
    Button createServerButton;

    Stage stage;

    int mode = -1;
    server selectedServer = null;
    public interface stateCallback {
        void stateActions(int mode, server selectedServer);
    }
    stateCallback callback;

    static class server {
        String name;
        InetAddress addr;
        int port;

        public server(String name, InetAddress addr,int port) {
            this.name = name;
            this.addr = addr;
            this.port = port;
        }
    }
    class listener extends Thread{
        DatagramSocket socket;
        ArrayList<server> serverList;
        public listener(ArrayList<server> serverList) throws SocketException {
            this.socket = new DatagramSocket(38879);
            socket.setSoTimeout(2000);
            this.serverList = serverList;
        }

        @Override
        public void run() {
            while(!Thread.currentThread().isInterrupted()) {
                System.out.println(Thread.currentThread().isInterrupted());
                try {
                    byte[] buf = new byte[256];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    this.socket.receive(packet);
                    System.out.println(Arrays.toString(packet.getData()));
                    byte[] temp = packet.getData();
                    String[] details = {"","",""}; // name, ip, port

                    int cnt = 0;
                    for (int i = 0; i != temp.length; i++) {
                        if (temp[i] == 32) { // if byte is equal to ascii code 32( )
                            cnt += 1;
                            continue;
                        }
                        if (temp[i] == 0) {
                            break; // EOF
                        }
                        details[cnt] += (char) temp[i];
                    }
                    System.out.println(Arrays.toString(details));
                    System.out.println(packet.getAddress());
                    server discoveredServer = new server(details[0], packet.getAddress(), Integer.parseInt(details[2]));
                    System.out.println("pre-sync");
                    synchronized (this.serverList) {
                        System.out.println("pre-sync");
                        serverList.add(discoveredServer);
                    }
                } catch (IOException e) {}
            }

            // closing actions
            socket.close();
        }
    }

    class advertiser extends Thread {
        ArrayList<server> list;
        public advertiser(ArrayList<server> serverList) {
            this.list = serverList;
        }

        @Override
        public void run() {
            while(!Thread.currentThread().isInterrupted()) {
                try {
                    // search for servers
                    synchronized (this.list) {
                        // clear previously found servers, if they still exist they will echo back
                        this.list.clear();
                    }
                    DatagramSocket socket = new DatagramSocket();
                    socket.setBroadcast(true);

                    byte[] buf = "*wave*".getBytes();
                    DatagramPacket packet = new DatagramPacket(buf, buf.length, InetAddress.getByName("255.255.255.255"), 38878);
                    socket.send(packet);
                    socket.close();

                    Thread.sleep(15000); // wait 15 seconds, no need to spam broadcasts
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

    }

    Thread responseListener;
    Thread advertiser;
    public serverBrowser(String title, stateCallback stateCallback) throws IOException, InterruptedException {
        callback = stateCallback; // callback function

        FXMLLoader loader = new FXMLLoader(getClass().getResource("serverBrowserUI.fxml"));
        loader.setController(this);
        Parent root = loader.load();
        Scene scene = new Scene(root);

        ArrayList<server> serverList = new ArrayList();

        stage = new Stage();
        stage.setScene(scene);
        stage.setTitle(title + " | state select");
        stage.setMinWidth(scene.getWidth());
        stage.setMinHeight(scene.getHeight());

        createServerButton.setOnMouseClicked(event -> {
            mode = 0; // create server mode
            responseListener.interrupt();
            advertiser.interrupt();
            callback.stateActions(mode,selectedServer);
            stage.close();
        }); // server creation function

        // stage.showAndWait();
        responseListener = new Thread(new listener(serverList));
        responseListener.start();
        advertiser = new Thread(new advertiser(serverList));
        advertiser.start();

        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(2), event -> {
            Platform.runLater(() -> {
                synchronized (serverList) {
                    // clear all elements
                    System.out.println("Before clearing: " + this.serverList.getChildren().size());
                    this.serverList.getChildren().clear();
                    System.out.println("After clearing: " + this.serverList.getChildren().size());
                    // add ui elements for all availiable servers
                    for (int i = 0; i != serverList.size(); i++) {
                        this.serverList.getChildren().add(serverDetails(serverList.get(i)));
                    }
                }
            });
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();

        stage.showAndWait();
    }

    public Node serverDetails(server server) {
        VBox labelBox = new VBox();
        VBox.setMargin(labelBox, new Insets(20,20,20,20));
        labelBox.setStyle("-fx-border-width: 2; -fx-border-color: black;");

        // Labels
        javafx.scene.control.Label studioName = new Label(server.name);
        VBox.setMargin(studioName,new Insets(0,0,0,20));
        javafx.scene.control.Label address = new Label(server.addr.toString().replace("/","") + ":" + server.port);
        VBox.setMargin(address,new Insets(0,0,0,20));
        labelBox.getChildren().add(studioName);
        labelBox.getChildren().add(address);

        labelBox.setOnMouseClicked(event -> {
            mode = 1; // join server mode
            selectedServer = server;
            responseListener.interrupt();
            advertiser.interrupt();
            callback.stateActions(mode,selectedServer);
            stage.close();
        }); // server joining function

        return labelBox;
    }
}
