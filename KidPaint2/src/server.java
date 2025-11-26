import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.xml.crypto.Data;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;

public class server extends Thread {

    class action { }

    class client {
        String name;
        Socket socket;
        public client(String name, Socket socket) {
            this.name = name;
            this.socket = socket;
        }
    }

    private ArrayList<BufferedImage> animationFrames = new ArrayList<>();
    private boolean isRecording = false ;
    private String recorderUsername = "" ;
    private ArrayList<client> disconnected = new ArrayList<>();
    //string tracks all actions(the drawing history)
    String actionHistory = "";

    class onBoarding extends Thread{
        private ServerSocket serverSocket;
        private ArrayList<client> clientList;
        String groupName;

        public onBoarding(ServerSocket serverSocket, ArrayList<client> clientList, String groupName) {
            this.serverSocket = serverSocket;
            this.clientList = clientList;
            this.groupName = groupName;
        }

        @Override
        public void run() {
            while(true) {
                try {
                    Socket temp = this.serverSocket.accept();
                    DataInputStream in = new DataInputStream(temp.getInputStream());
                    DataOutputStream out = new DataOutputStream(temp.getOutputStream());

                    switch (in.readInt()) {
                        case 101:
                            String name = "";
                            byte[] data = in.readNBytes(in.readInt());
                            for (int i = 0; i != data.length; i++) {
                                name += (char) data[i];
                            }
                            client newClient = new client(name,temp);
                            synchronized (this.clientList) {
                                this.clientList.add(newClient);
                            }
                            System.out.println("[server] onboarded player: " + name);
                            out.writeInt(100);
                            out.flush();
                            break;
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    class advertiser extends Thread {
        DatagramSocket server;
        int port = 38878;
        private byte[] message;
        private byte[] buf = new byte[256];

        public advertiser(int port,InetAddress addr, String name) throws SocketException {
            server = new DatagramSocket(this.port);
            message = (name + " " + addr.toString() + " " + port).getBytes();
        }

        @Override
        public void run() {
            while(true) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf,buf.length);
                    server.receive(packet);
                    byte[] data = packet.getData();
                    String temp = "";
                    int index = 0;
                    while (true) {
                        if (data[index] == 0) { break; }
                        temp += (char) data[index++];
                    }
                    if (temp.equals("*wave*")) {
                        System.out.println("[server] broadcast received");
                        InetAddress sender = packet.getAddress();
                        int port = 38879;
                        DatagramPacket advert = new DatagramPacket(message,message.length,sender,port);
                        server.send(advert);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    int port = 38877;
    ArrayList<client> clientList = new ArrayList<>();
    String name;
    ServerSocket serverSocket;

    public server(String groupName, serverSharedData data) throws IOException {
        serverSocket = new ServerSocket(port);
        System.out.println("inet: " + serverSocket.getInetAddress().getHostAddress());
        data.setData(serverSocket.getInetAddress(),port);
        name = groupName;

        Thread onboard = new Thread(new onBoarding(serverSocket,clientList,name));
        Thread advertise = new Thread(new advertiser(port,serverSocket.getInetAddress(),name));
        onboard.start();
        advertise.start();

        System.out.println("Server up...");
        System.out.println("bound ports: \n-38877\n-38878");
    }

    @Override
    public void run() {
        while (true) {
            synchronized (clientList) {
                for (int i = 0; i != clientList.size(); i++) {
                    try {
                        client temp = clientList.get(i);
                        DataInputStream in = new DataInputStream(temp.socket.getInputStream());
                        DataOutputStream out = new DataOutputStream(temp.socket.getOutputStream());
                        if (in.available() != 0) {
                            int actionCode = in.readInt();
                            switch (actionCode) {
                                case 202:
                                    System.out.println("[server] Action code 202");
                                    String message = "echo";
                                    out.writeInt(message.length());
                                    out.writeBytes(message);
                                    out.flush();
                                    break;

                                case 250:
                                    int length = actionHistory.length();
                                    byte[] history = actionHistory.getBytes();
                                    out.writeInt(250);
                                    out.writeInt(length);
                                    out.write(history);
                                    out.flush();
                                    break;

                                case 300:
                                    int size = in.readInt();
                                    byte[] chat = in.readNBytes(size);
                                    System.out.println("[server] Action code 300 - Message");
                                    broadcastMessage(chat, 300);
                                    break;

                                case 400: //add history to the server side array (drawing history)
                                    int len= in.readInt();
                                    byte[] drawingUpdate = in.readNBytes(len);
                                    actionHistory+= new String(drawingUpdate);
                                    System.out.println("[server] Action code 400 - Drawing Action");
                                    broadcastMessage(drawingUpdate, 400);
                                    break;

                                case 401:
                                    System.out.println("[server] Action code 401 : Start Recording ");
                                    int nameLen = in.readInt();
                                    String username = new String (in.readNBytes(nameLen));
                                    recorderUsername = username ;
                                    isRecording = true ;
                                    animationFrames.clear();
                                    break ;

                                case 402 :
                                    System.out.println("[server] Action code 402 : Stop Recording ");
                                    isRecording = false ;
                                    break ;

                                case 405 :
                                    System.out.println("[server] Action code 405 : Receive Frame ");
                                    int frameSize = in.readInt();
                                    byte[] imgData = in.readNBytes(frameSize);
                                    BufferedImage frame = ImageIO.read(new ByteArrayInputStream(imgData));
                                    if (isRecording) {
                                        animationFrames.add(frame);
                                        System.out.println("[server] Frame size：" + animationFrames.size());
                                    } else {
                                        System.out.println("[server] not record");
                                    }
                                    break ;

                                case 406 :
                                    System.out.println("[server] Action code 406 : Export Recording to GIF ");
                                    if (!isRecording && temp.name.equals(recorderUsername)){
                                        int pathLen = in.readInt();
                                        String filePath = new String (in.readNBytes(pathLen));
                                        exportFramesToGIF(animationFrames, filePath);
                                        System.out.println("[server] Exported recording to " + filePath);
                                    } else {
                                        System.out.println("[server] Fail to export");
                                    }
                                    break ;

                                default:
                                    System.out.println("[server] unknown actionCode：" + actionCode);
                                    break;
                            }
                        }
                    } catch (IOException e) {
                        disconnected.add(clientList.get(i));
                        clientList.remove(i);
                        i--;
                    }
                }
            }
            clientList.removeAll(disconnected);
            disconnected.clear();
        }
    }


    private void broadcastMessage(byte[] data, int actionCode) {
        synchronized (clientList) {
            for (client c : clientList) {
                try {
                    DataOutputStream clientOut = new DataOutputStream(c.socket.getOutputStream());
                    clientOut.writeInt(actionCode);
                    clientOut.writeInt(data.length);
                    clientOut.write(data);
                    clientOut.flush();
                } catch (IOException e) {
                    disconnected.add(c);
                }
            }
        }
    }


    private void exportFramesToGIF(ArrayList<BufferedImage> animationFrames, String filePath) {
        try{
            File outputFile = new File(filePath);

            Iterator<ImageWriter> writerIter = ImageIO.getImageWritersByFormatName("gif");
            if (!writerIter.hasNext()) {
                return;
            }
            ImageWriter writer = writerIter.next();
            ImageOutputStream ios = ImageIO.createImageOutputStream(outputFile);
            writer.setOutput(ios);


            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            javax.imageio.metadata.IIOMetadata metadata = writer.getDefaultImageMetadata(
                    javax.imageio.ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB), writeParam);
            String metadataFormat = metadata.getNativeMetadataFormatName();
            javax.imageio.metadata.IIOMetadataNode root = (javax.imageio.metadata.IIOMetadataNode) metadata.getAsTree(metadataFormat);


            javax.imageio.metadata.IIOMetadataNode graphicsControlNode = new javax.imageio.metadata.IIOMetadataNode("GraphicControlExtension");
            graphicsControlNode.setAttribute("disposalMethod", "none");
            graphicsControlNode.setAttribute("userInputFlag", "FALSE");
            graphicsControlNode.setAttribute("transparentColorFlag", "FALSE");
            graphicsControlNode.setAttribute("delayTime", "40");
            graphicsControlNode.setAttribute("transparentColorIndex", "0");


            javax.imageio.metadata.IIOMetadataNode appExtNode = new javax.imageio.metadata.IIOMetadataNode("ApplicationExtensions");
            javax.imageio.metadata.IIOMetadataNode appNode = new javax.imageio.metadata.IIOMetadataNode("ApplicationExtension");
            appNode.setAttribute("applicationID", "NETSCAPE");
            appNode.setAttribute("authenticationCode", "2.0");
            appNode.setUserObject(new byte[]{0x1, 0x0, 0x0});

            root.appendChild(graphicsControlNode);
            root.appendChild(appExtNode);
            appExtNode.appendChild(appNode);
            metadata.setFromTree(metadataFormat, root);


            writer.prepareWriteSequence(null);
            for (BufferedImage frame : animationFrames) {
                writer.writeToSequence(new IIOImage(frame, null, metadata), writeParam);
            }
            writer.endWriteSequence();


            ios.close();
            writer.dispose();
            System.out.println("[server] GIF export success：" + filePath);
        }catch (Exception e){
            System.out.println("[server] GIF wrond export：" + e.getMessage());
            e.printStackTrace();
        }
    }
}