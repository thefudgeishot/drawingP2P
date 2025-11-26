import javafx.application.Application;
import javafx.stage.Stage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

class serverSharedData {
    InetAddress serverInet;
    int serverPort;

    public synchronized void setData(InetAddress inet, int port) {
        this.serverInet = inet;
        this.serverPort = port;
    }

    public synchronized InetAddress getInet() {
        return this.serverInet;
    }

    public synchronized int getPort() {
        return this.serverPort;
    }
}

public class KidPaint2 extends Application {
    final static String title = "KidPaint 2.0";
    int state;

    server hostServer; // can be null, depending on launch state
    Socket serverSocket;

    @Override
    public void start(Stage stage) throws Exception {
        GetNameDialog dialog = new GetNameDialog(title, "Please select a username...");
        String username = dialog.playername;
        stage.setTitle(title);

        final int[] applicationMode = new int[1];
        final serverBrowser.server[] chosenServer = new serverBrowser.server[1];
        serverBrowser browser = new serverBrowser(title, new serverBrowser.stateCallback() {
            @Override
            public void stateActions(int mode, serverBrowser.server selectedServer) {
                applicationMode[0] = mode;
                chosenServer[0] = selectedServer;
            }
        });

        serverBrowser.server host = null;

        if (applicationMode[0] == 0) {
            // create server as a thread
            GetNameDialog groupName = new GetNameDialog(title, "Please select a group name...");
            String serverName = groupName.getPlayername();

            serverSharedData data = new serverSharedData();

            Thread server = new Thread(new server(serverName,data));
            server.start();
            System.out.println(data.getInet());
            System.out.println(data.getPort());

            // convert data to generic
            host = new serverBrowser.server(serverName,data.getInet(),data.getPort());
        }
        // join server
        if (host == null) {
            host = chosenServer[0];
        }
        serverSocket = new Socket(host.addr,host.port);
        DataOutputStream out = new DataOutputStream(serverSocket.getOutputStream());
        DataInputStream in = new DataInputStream(serverSocket.getInputStream());

        // onboarding
        out.writeInt(101); // action code
        out.writeInt(username.length());
        out.writeBytes(username);
        out.flush(); // send

        while (in.available() == 0) {
            // await response
            Thread.sleep(100);
        }
        System.out.println();
        if (in.readInt() == 100) {
            // successfully onboarded
            out.writeInt(202); // echo
            out.flush(); // send

            while (in.available() == 0) {
                Thread.sleep(100);
            }
            int size = in.readInt();
            byte[] temp = in.readNBytes(size);

            String builder = "";
            for ( int i = 0; i != temp.length; i++) {
                builder += (char) temp[i];
            }
            System.out.println(builder);
        }
        client user = new client(serverSocket,username);
        MainWindow mainWindow = new MainWindow(stage,username,user);
        out.writeInt(250);
        out.flush();
    }
}
