import javafx.animation.AnimationTimer;
import javafx.fxml.FXMLLoader;
import javafx.fxml.FXML;

import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.scene.Parent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedList;

public class MainWindow {
    @FXML
    ChoiceBox<String> chbMode;

    @FXML
    Canvas canvas;

    @FXML
    Pane container;

    @FXML
    Pane panePicker;

    @FXML
    Pane paneColor;

    @FXML
    TextArea areaMsg;

    @FXML
    TextField txtMsg;

    @FXML
    Button btnSend;

    client client;
    String username;
    int numPixels = 50;
    Stage stage;
    AnimationTimer animationTimer;
    int[][] data;
    double pixelSize, padSize, startX, startY;
    int selectedColorARGB;
    boolean isPenMode = true;
    LinkedList<Point> filledPixels = new LinkedList<Point>();

    class Point{
        int x, y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public MainWindow(Stage stage, String username, client client) throws IOException {
        this.username = username;
        this.client = client;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("mainWindownUI.fxml"));
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
    }

    /**
     * Update canvas info when the window is resized
     */
    void onCanvasSizeChange() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        padSize = Math.min(w, h);
        startX = (w - padSize)/2;
        startY = (h - padSize)/2;
        pixelSize = padSize / numPixels;
    }

    /**
     * terminate this program
     */
    void quit() {
        System.out.println("Bye bye");
        stage.close();
        System.exit(0);
    }

    /**
     * Initialize UI components
     * @throws IOException
     */
    void initial() throws IOException {
        data = new int[numPixels][numPixels];

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
        });

        btnSend.setOnMouseClicked(event-> sendMessage());

        new Thread(()->{
            try {
                DataInputStream msgIn = new DataInputStream(client.serverSocket.getInputStream());
                while(true){
                    int actioncode = msgIn.readInt();
                    if(actioncode==300){
                        int size = msgIn.readInt();
                        byte[] message = msgIn.readNBytes(size);
                        String Message = new String(message);

                        javafx.application.Platform.runLater(() ->{
                            areaMsg.appendText(Message + "\n");
                        });
                    }
                }
            }
            catch (IOException ex){
                ex.printStackTrace();
            }

        }).start();

        initColorMap();
    }

    /**
     * Initialize color map
     * @throws IOException
     */
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

    /**
     * Pick a color from the color map image
     * @param image color map image
     * @param imgX x position in the image
     * @param imgY y position in the image
     * @param imageWidth the width of the image
     * @param imageHeight the height of the image
     */
    void pickColor(Image image, int imgX, int imgY, double imageWidth, double imageHeight) {
        if (imgX >= 0 && imgX < imageWidth && imgY >= 0 && imgY < imageHeight) {
            PixelReader reader = image.getPixelReader();

            selectedColorARGB = reader.getArgb(imgX, imgY);

            Color color = reader.getColor(imgX, imgY);
            paneColor.setStyle("-fx-background-color:#" + color.toString().substring(2));
        }
    }

    /**
     * Invoked when the Pen mode is used. Update sketch data array and store updated pixels in a list named filledPixels
     * @param mx mouse down/drag position x
     * @param my mouse down/drag position y
     */
    void penToData(double mx, double my) {
        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
            int row = (int) ((my - startY) / pixelSize);
            int col = (int) ((mx - startX) / pixelSize);
            data[row][col] = selectedColorARGB;
            filledPixels.add(new Point(col, row));
        }
    }

    /**
     * Invoked when the Bucket mode is used. It calls paintArea() to update sketch data array and store updated pixels in a list named filledPixels
     * @param mx mouse down/drag position x
     * @param my mouse down/drag position y
     */
    void bucketToData(double mx, double my) {
        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
            int row = (int) ((my - startY) / pixelSize);
            int col = (int) ((mx - startX) / pixelSize);
            paintArea(col, row);
        }
    }

    /**
     * Update the color of specific area
     * @param col position of the sketch data array
     * @param row position of the sketch data array
     */
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

    /**
     * Convert argb value from int format to JavaFX Color
     * @param argb
     * @return Color
     */
    Color fromARGB(int argb) {
        return Color.rgb(
                (argb >> 16) & 0xFF,
                (argb >> 8) & 0xFF,
                argb & 0xFF,
                ((argb >> 24) & 0xFF) / 255.0
        );
    }


    /**
     * Render the sketch data to the canvas
     */
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
        }
        catch (IOException ex){
            ex.printStackTrace();
        }
    }


}
