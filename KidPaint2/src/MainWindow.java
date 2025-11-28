import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.fxml.FXMLLoader;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.Parent;
import javafx.util.Duration;
import javafx.scene.control.Button;
import javafx.animation.Timeline;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MainWindow {
    @FXML ChoiceBox<String> chbMode;
    @FXML Canvas canvas;
    @FXML Pane container;
    @FXML Pane panePicker;
    @FXML Pane paneColor;
    @FXML TextArea areaMsg;
    @FXML TextField txtMsg;
    @FXML Button btnSend;
    @FXML Button StartRecordBtn;
    @FXML Button PauseRecordBtn;
    @FXML Button ExportGifBtn;
    @FXML Button Savebtn;
    @FXML TextField txtLoadFile;
    @FXML Button btnLoad;

    client client;
    String username;
    int numPixels = 50;
    Stage stage;
    AnimationTimer animationTimer;
    int[][] data = new int[numPixels][numPixels];
    int[][] initial= data;
    double pixelSize, padSize, startX, startY;
    int selectedColorARGB;
    boolean isPenMode = true;
    LinkedList<Point> filledPixels = new LinkedList<Point>();


    private ArrayList<BufferedImage> animationSteps = new ArrayList<>();
    private Timeline catchUpTimeline;
    private int currentStep = 0;
    private boolean isRecording = false;
    private String recorderName = "";
    private boolean isPlaying = false;

    private BlockingQueue<ServerMessage> messageQueue = new LinkedBlockingQueue<>();
    private boolean isListening = true;


    static class ServerMessage {
        int actionCode;
        byte[] data;
        public ServerMessage(int actionCode, byte[] data) {
            this.actionCode = actionCode;
            this.data = data;
        }
    }

    class Point {
        int x, y;
        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public MainWindow(Stage stage, String username, client client) throws IOException {
        this.username = username;
        this.client = client;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("mainWindowUI.fxml"));
        loader.setController(this);
        Parent root = loader.load();
        Scene scene = new Scene(root);

        this.stage = stage;
        stage.setScene(scene);
        stage.setMinWidth(scene.getWidth());
        stage.setMinHeight(scene.getHeight());

        canvas.widthProperty().bind(container.widthProperty());
        canvas.heightProperty().bind(container.heightProperty());
        canvas.widthProperty().addListener(w->onCanvasSizeChange());
        canvas.heightProperty().addListener(h->onCanvasSizeChange());

        stage.setOnCloseRequest(event -> quit());
        stage.show();
        initial();
        animationTimer.start();


        catchUpTimeline = new Timeline(new KeyFrame(Duration.millis(400), e -> playNextStep()));
        catchUpTimeline.setCycleCount(Timeline.INDEFINITE);

        AnimationButtons();
        InitializerThread();
        startUnifiedListenerThread();

        startMessageProcessingThread();
    }


    void AnimationButtons(){
        StartRecordBtn.setOnAction(e-> startRecording());
        PauseRecordBtn.setOnAction(e-> pauseRecording());
        ExportGifBtn.setOnAction(e-> exportGif());
        Savebtn.setOnAction(e-> saveImage());
        btnLoad.setOnAction(e -> loadRequest());
    }


    void saveImage(){
        try{
            DataOutputStream out = new DataOutputStream(client.serverSocket.getOutputStream());
            out.writeInt(500);
            out.flush();
        }
        catch(IOException e){
            e.printStackTrace();
        }

    }

    void loadRequest(){
        String filename = txtLoadFile.getText().trim();
        if (!filename.isEmpty()) {
            try {
                DataOutputStream out = new DataOutputStream(client.serverSocket.getOutputStream());
                out.writeInt(501);
                byte[] nameBytes = filename.getBytes();
                out.writeInt(nameBytes.length);
                out.write(nameBytes);
                out.flush();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    void startRecording(){
        try{
            DataOutputStream out = new DataOutputStream(client.serverSocket.getOutputStream());

            out.writeInt(401);
            out.writeInt(username.length());
            out.writeBytes(username);
            out.flush();

            isRecording = true;
            recorderName = username;
            updateButtonStates();


            BufferedImage initialFrame = new BufferedImage(numPixels, numPixels, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < numPixels; y++) {
                for (int x = 0; x < numPixels; x++) {
                    initialFrame.setRGB(x, y, data[y][x]);
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(initialFrame, "png", baos);
            out.writeInt(405);
            out.writeInt(baos.size());
            out.write(baos.toByteArray());
            out.flush();

        }catch (IOException e){
            e.printStackTrace();
        }
    }


    void pauseRecording() {
        try {
            DataOutputStream out = new DataOutputStream(client.serverSocket.getOutputStream());
            out.writeInt(402);
            out.flush();
            isRecording = false;
            updateButtonStates();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void exportGif() {
        if (isRecording || isPlaying || !recorderName.equals(username)) {
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Animation as GIF");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GIF files (*.gif)", "*.gif"));
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try {
                DataOutputStream out = new DataOutputStream(client.serverSocket.getOutputStream());
                out.writeInt(406);
                out.writeInt(file.getAbsolutePath().length());
                out.writeBytes(file.getAbsolutePath());
                out.flush();

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    void sendCanvasData(){
        if (!isRecording) return;
        try {
            BufferedImage frame = new BufferedImage(numPixels, numPixels, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < numPixels; y++) {
                for (int x = 0; x < numPixels; x++) {
                    frame.setRGB(x, y, data[y][x]);
                }
            }

            DataOutputStream out = new DataOutputStream(client.serverSocket.getOutputStream());
            out.writeInt(405);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(frame, "png", baos);
            out.writeInt(baos.size());
            out.write(baos.toByteArray());
            out.flush();


        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void startUnifiedListenerThread() {
        new Thread(() -> {
            try {
                DataInputStream in = new DataInputStream(client.serverSocket.getInputStream());
                while (isListening) {
                    if (in.available() > 0) {
                        int actionCode = in.readInt();
                        int dataLen = in.readInt();
                        byte[] data = in.readNBytes(dataLen);
                        messageQueue.put(new ServerMessage(actionCode, data));

                    }
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                e.printStackTrace();

            }
        }).start();
    }


    private void startMessageProcessingThread() {
        new Thread(() -> {
            try {
                while (isListening) {
                    ServerMessage msg = messageQueue.take();
                    switch (msg.actionCode) {
                        case 300:
                            handleChatMessage(msg.data);
                            break;
                        case 400:
                            handleDrawingUpdate(msg.data);
                            break;
                        case 404:
                            handleAnimationFrames(msg.data);
                            break;
                        case 501:
                            handleLoadImage(msg.data);
                        default:
                            System.out.println("[Client] unknown actionCode：" + msg.actionCode);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }


    private void handleLoadImage(byte[] loadData){
        String actionstring = new String(loadData);
        for (int row = 0; row < numPixels; row++) {
            for (int col = 0; col < numPixels; col++) {
                data[row][col] = 0;
            }
        }

        String[] actionlist = actionstring.split(";");
        for (String action : actionlist) {
            if (action.isEmpty()) continue;
            String[] parts = action.split(",");
            int col = Integer.parseInt(parts[0]);
            int row = Integer.parseInt(parts[1]);
            int argb = Integer.parseInt(parts[2]);
            data[row][col] = argb;
        }
        javafx.application.Platform.runLater(this::render);
    }


    private void handleChatMessage(byte[] data) {
        String message = new String(data);
        javafx.application.Platform.runLater(() -> {
            areaMsg.appendText(message + "\n");
        });
    }


    private void handleDrawingUpdate(byte[] drawingData) {
        String actionstring = new String(drawingData); // Use the renamed parameter
        String[] actionlist = actionstring.split(";");
        for (String action : actionlist) {
            if (action.isEmpty()) continue;
            String[] parts = action.split(",");
            int col = Integer.parseInt(parts[0]);
            int row = Integer.parseInt(parts[1]);
            int argb = Integer.parseInt(parts[2]);
            data[row][col] = argb; // Now "data" correctly refers to the class-level int[][] pixel array
        }
        javafx.application.Platform.runLater(this::render);
    }


    private void handleAnimationFrames(byte[] data) {
        try {

            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream in = new DataInputStream(bais);

            int frameCount = in.readInt();


            animationSteps.clear();
            for (int i = 0; i < frameCount; i++) {
                int frameLen = in.readInt();
                byte[] frameData = new byte[frameLen];
                in.readFully(frameData);

                BufferedImage img = ImageIO.read(new ByteArrayInputStream(frameData));
                if (img == null) {
                    return ;
                } else {
                    animationSteps.add(img);
                }
            }

            javafx.application.Platform.runLater(() -> {
                currentStep = 0;
                if (animationSteps.isEmpty()) {
                    isPlaying = false;
                    updateButtonStates();
                    return;
                }
                catchUpTimeline.playFromStart();
            });

        } catch (Exception e) {
            e.printStackTrace();
            javafx.application.Platform.runLater(() -> {
                isPlaying = false;
                updateButtonStates();
            });
        }
    }


    void playNextStep(){
        if(currentStep >= animationSteps.size()){
            catchUpTimeline.stop();
            isPlaying = false;
            enableInputs();
            updateButtonStates();
            return;
        }

        BufferedImage frame = animationSteps.get(currentStep);

        for (int y = 0; y < numPixels; y++) {
            for (int x = 0; x < numPixels; x++) {
                data[y][x] = frame.getRGB(x, y);
            }
        }

        render();
        currentStep++;
    }

    private void updateButtonStates() {
        StartRecordBtn.setDisable(isRecording || isPlaying);
        PauseRecordBtn.setDisable(!isRecording || isPlaying || !recorderName.equals(username));
        ExportGifBtn.setDisable(isRecording || isPlaying || !recorderName.equals(username));

        StartRecordBtn.setText(isRecording ? "Recording..." : "Start Rec");
        PauseRecordBtn.setText(isRecording ? "Pause Rec" : "Paused");
    }

    private void enableInputs() {
        canvas.setDisable(false);
        chbMode.setDisable(false);
    }

    void onCanvasSizeChange() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        padSize = Math.min(w, h);
        startX = (w - padSize)/2;
        startY = (h - padSize)/2;
        pixelSize = padSize / numPixels;
    }

    void quit() {
        isListening = false;
        System.out.println("Bye bye");
        stage.close();
        System.exit(0);
    }

    void InitializerThread(){
        new Thread(()->{
            try{
                DataInputStream in = new DataInputStream(client.serverSocket.getInputStream());
                if(in.readInt()==250){
                    int len = in.readInt();
                    byte[] history = in.readNBytes(len);
                    loadHistory(history);
                }
            }
            catch(IOException e){
                e.printStackTrace();
            }
        }).start();
    }

    void loadHistory(byte[] history){
        String actionhistory = new String(history);
        String[] actionhistoryarray = actionhistory.split(";");
        for(String action : actionhistoryarray){
            if (action.isEmpty()) continue;
            String[] parts = action.split(",");
            int col = Integer.parseInt(parts[0]);
            int row = Integer.parseInt(parts[1]);
            int argb = Integer.parseInt(parts[2]);
            initial[row][col] = argb;
        }
    }

    void initial() throws IOException {
        data = initial;

        animationTimer = new AnimationTimer() {
            @Override
            public void handle(long l) {
                render();
            }
        };

        chbMode.setValue("Pen");

        canvas.setOnMousePressed(event -> {
            isPenMode = chbMode.getValue().equals("Pen");
            filledPixels.clear();
            if (isPenMode)
                penToData(event.getX(), event.getY());
        });

        canvas.setOnMouseDragged(event -> {
            if (isPenMode)
                penToData(event.getX(), event.getY());
        });


        canvas.setOnMouseReleased(event->{
            if (!isPenMode)
                bucketToData(event.getX(), event.getY());
            sendCanvasData();
            if(!filledPixels.isEmpty()){
                drawToData(filledPixels);
                filledPixels.clear();
            }
        });

        btnSend.setOnMouseClicked(event-> sendMessage());
        initColorMap();
    }


    void initColorMap() throws IOException {
        Image image = new Image("file:color_map.png");
        ImageView imageView = new ImageView(image);
        imageView.setFitHeight(30.0);
        imageView.setPreserveRatio(true);
        panePicker.getChildren().add(imageView);

        double imageWidth = image.getWidth();
        double imageHeight = image.getHeight();
        double viewWidth = imageView.getBoundsInParent().getWidth();
        double viewHeight = imageView.getBoundsInParent().getHeight();

        double scaleX = imageWidth / viewWidth;
        double scaleY = imageHeight / viewHeight;

        pickColor(image, 0, 0, imageWidth, imageHeight);

        panePicker.setOnMouseClicked(event -> {
            double x = event.getX();
            double y = event.getY();
            int imgX = (int)(x * scaleX);
            int imgY = (int)(y * scaleY);
            pickColor(image, imgX, imgY, imageWidth, imageHeight);
        });
    }

    void pickColor(Image image, int imgX, int imgY, double imageWidth, double imageHeight) {
        if (imgX >= 0 && imgX < imageWidth && imgY >= 0 && imgY < imageHeight) {
            PixelReader reader = image.getPixelReader();
            selectedColorARGB = reader.getArgb(imgX, imgY);
            Color color = reader.getColor(imgX, imgY);
            paneColor.setStyle("-fx-background-color:#" + color.toString().substring(2));
        }
    }

    void penToData(double mx, double my) {
        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
            int row = (int) ((my - startY) / pixelSize);
            int col = (int) ((mx - startX) / pixelSize);
            data[row][col] = selectedColorARGB;
            filledPixels.add(new Point(col, row));
        }
    }

    void bucketToData(double mx, double my) {
        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
            int row = (int) ((my - startY) / pixelSize);
            int col = (int) ((mx - startX) / pixelSize);
            paintArea(col, row);
        }
    }

    public void paintArea(int col, int row) {
        int oriColor = data[row][col];
        LinkedList<Point> buffer = new LinkedList<Point>();
        if (oriColor != selectedColorARGB) {
            buffer.add(new Point(col, row));
            while(!buffer.isEmpty()) {
                Point p = buffer.removeFirst();
                col = p.x;
                row = p.y;
                if (data[row][col] != oriColor) continue;
                data[row][col] = selectedColorARGB;
                filledPixels.add(p);
                if (col > 0 && data[row][col-1] == oriColor) buffer.add(new Point(col-1, row));
                if (col < data[0].length - 1 && data[row][col+1] == oriColor) buffer.add(new Point(col+1, row));
                if (row > 0 && data[row-1][col] == oriColor) buffer.add(new Point(col, row-1));
                if (row < data.length - 1 && data[row+1][col] == oriColor) buffer.add(new Point(col, row+1));
            }
        }
    }

    Color fromARGB(int argb) {
        return Color.rgb(
                (argb >> 16) & 0xFF,
                (argb >> 8) & 0xFF,
                argb & 0xFF,
                ((argb >> 24) & 0xFF) / 255.0
        );
    }

    void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        double x = startX;
        double y = startY;
        gc.setStroke(Color.GRAY);
        for (int col = 0; col< numPixels; col++) {
            for (int row = 0; row< numPixels; row++) {
                gc.setFill(fromARGB(data[col][row]));
                gc.fillOval(x, y, pixelSize, pixelSize);
                gc.strokeOval(x, y, pixelSize, pixelSize);
                x += pixelSize;
            }
            x = startX;
            y += pixelSize;
        }
    }

    void sendMessage(){
        try{
            String msg = username + ":- " + txtMsg.getText();
            byte[] data = msg.getBytes();
            DataOutputStream msgOut = new DataOutputStream(client.serverSocket.getOutputStream());
            msgOut.writeInt(300);
            msgOut.writeInt(data.length);
            msgOut.write(data);
            msgOut.flush();
            txtMsg.clear();
        } catch (IOException ex){
            ex.printStackTrace();
        }
    }

    void drawToData(LinkedList<Point> pixels){
        try{
            StringBuilder p2b = new StringBuilder();
            for (Point p : pixels) {
                p2b.append(p.x).append(",").append(p.y).append(",").append(selectedColorARGB).append(";");
            }
            byte[] drawingUpdate = p2b.toString().getBytes();
            DataOutputStream out = new DataOutputStream(client.serverSocket.getOutputStream());
            out.writeInt(400);
            out.writeInt(drawingUpdate.length);
            out.write(drawingUpdate);
            out.flush();
        } catch(IOException ex){
            ex.printStackTrace();
        }
    }
}