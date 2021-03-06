public class Main {
    public static void main(String argv[]) throws Exception
    {
        SharedArea sharedArea = new SharedArea();
        VideoStream videoStream = new VideoStream();
        sharedArea.start_flag = false;
        sharedArea.file_flag = false;
        sharedArea.filelist = "";
        sharedArea.disconnect_flag = false;


        Wifi wifi = new Wifi(videoStream, sharedArea);
        Server server = new Server(videoStream, sharedArea, wifi);
        FileServer fileServer = new FileServer(5522, sharedArea);

        Thread server_thread = new Thread(server);
        Thread wifi_thread = new Thread(wifi);
        Thread fileServer_thread = new Thread(fileServer);

        server_thread.start();
        wifi_thread.start();
        fileServer_thread.start();
    }
}

class SharedArea {
    String filelist;
    boolean file_flag;
    boolean start_flag;
    boolean disconnect_flag;
    String clientIP;
}
