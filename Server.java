/* ------------------
   Server
   usage: java Server [RTSP listening port]
   ---------------------- */


import java.io.*;
import java.net.*;
import java.awt.*;
import java.util.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.Timer;

public class Server extends JFrame implements ActionListener {

    //RTP variables:
    //----------------
    DatagramSocket RTPsocket; //socket to be used to send and receive UDP packets
    private DatagramPacket senddp; //UDP packet containing the video frames

    InetAddress ClientIPAddr;   //Client IP address
    private int RTP_dest_port = 0;      //destination port for RTP packets  (given by the RTSP Client)
    private int RTSP_dest_port = 0;

    //GUI:
    //----------------
    JLabel label;

    //Video variables:
    //----------------
    int imagenb = 0; //image nb of the image currently transmitted
    VideoStream video; //VideoStream object used to access video frames
    static int MJPEG_TYPE = 96; //RTP payload type for MJPEG video
    static int FRAME_PERIOD = 100; //Frame period of the video to stream, in ms
    static int VIDEO_LENGTH = 5000; //length of the video in frames

    Timer timer;    //timer used to send the images at the video frame rate
    byte[] buf;     //buffer used to store the images to send to the client 
    int sendDelay;  //the delay to send images over the wire. Ideally should be
                    //equal to the frame rate of the video file, but may be 
                    //adjusted when congestion is detected.

    //RTSP variables
    //----------------
    //rtsp states
    final static int INIT = 0;
    final static int READY = 1;
    final static int PLAYING = 2;
    //rtsp message types
    final static int SETUP = 3;
    final static int PLAY = 4;
    final static int PAUSE = 5;
    final static int TEARDOWN = 6;
    final static int DESCRIBE = 7;

    Socket RTSPsocket; //socket used to send/receive RTSP messages
    //input and output stream filters

    static String RTSPid = UUID.randomUUID().toString(); //ID of the RTSP session
    int RTSPSeqNb = 0; //Sequence number of RTSP messages within the session


    //RTCP variables
    //----------------
    static int RTCP_PERIOD = 400;     //How often to check for control events
    DatagramSocket RTCPsocket;
    RtcpReceiver rtcpReceiver;
    int congestionLevel;

    //Performance optimization and Congestion control
    ImageTranslator imgTranslator;
    CongestionController cc;
    
    final static String CRLF = "\r\n";

    //--------------------------------
    //Constructor
    //--------------------------------
    public Server(int RTSPport, ServerSocket listenSocket) throws IOException {

        //init Frame
        super("RTSP Server");

        //init RTP sending Timer
        sendDelay = FRAME_PERIOD;
        timer = new Timer(sendDelay, this);
        timer.setInitialDelay(0);
        timer.setCoalesce(true);

        //init congestion controller
        cc = new CongestionController(600, this);

        //allocate memory for the sending buffer
        buf = new byte[20000]; 

        //Handler to close the main window
        addWindowListener(new WindowAdapter() {
        public void windowClosing(WindowEvent e) {
          //stop the timer and exit
            System.out.println("1111");
            timer.stop();
            rtcpReceiver.stopRcv();
            System.exit(0);
        }});

        //init the RTCP packet receiver
        rtcpReceiver = new RtcpReceiver(RTCP_PERIOD);

        //GUI:
        label = new JLabel("Send frame #        ", JLabel.CENTER);
        getContentPane().add(label, BorderLayout.CENTER);

        //Video encoding and quality
        imgTranslator = new ImageTranslator(0.8f);
        RTSP_dest_port = RTSPport;
        RTSPsocket = listenSocket.accept();
    }
          



    public void save_video() {
        FileOutputStream fos;
        Date today = new Date();
        String file_name = "hahah"+ ".h264";
	    try {
            fos = new FileOutputStream(file_name, true);
	    } catch(Exception fosE) {
            fosE.printStackTrace();
	    }
    }

    public String check_wifi() {
        byte[] bytes = new byte[1024];
        String wifi_name = "";
        try{
            Process process = new ProcessBuilder("iwconfig", "wlan0").start();
            InputStream input = process.getInputStream();
            int n = input.read(bytes, 0, 35);
            String str = new String(bytes);
            wifi_name = str.substring(29,35);
            System.out.println("wifi_name: "+wifi_name);
        } catch(IOException e4) {
            System.out.println("Exception Processor Builder: "+e4);
        }
        return wifi_name;
    }

    //------------------------
    //Handler for timer
    //------------------------
    public void actionPerformed(ActionEvent e) {
        byte[] frame;
       
	System.out.println("44444");
        if(check_wifi().equals("off/an")) {
		System.out.println("111");
		timer.stop();
		rtcpReceiver.stopRcv();
		return;
	}

        //if the current image nb is less than the length of the video
        if (imagenb < VIDEO_LENGTH) {
            //update current imagenb
            imagenb++;
	 
	    int image_length = 0 ;
            try {
                //get next frame to send from the video, as well as its size
                image_length = video.getnextframe(buf);
		for(int i=0; i<8; i++) {
			System.out.print(buf[i]);
		}
		System.out.println("");
	    } catch(Exception e2) {
		System.out.println("11" + e2.toString());
	    }

	    try {

                //adjust quality of the image if there is congestion detected
                if (congestionLevel > 0) {
                    imgTranslator.setCompressionQuality(1.0f - congestionLevel * 0.2f);
                    frame = imgTranslator.compress(Arrays.copyOfRange(buf, 0, image_length));
                    image_length = frame.length;
                    System.arraycopy(frame, 0, buf, 0, image_length);
                }
                //Builds an RTPpacket object containing the frame
                RTPpacket rtp_packet = new RTPpacket(MJPEG_TYPE, imagenb, imagenb*FRAME_PERIOD, buf, image_length);
                
                //get to total length of the full rtp packet to send
                int packet_length = rtp_packet.getlength();

                //retrieve the packet bitstream and store it in an array of bytes
                byte[] packet_bits = new byte[packet_length];

                rtp_packet.getpacket(packet_bits);

                //send the packet as a DatagramPacket over the UDP socket 
                senddp = new DatagramPacket(packet_bits, packet_length, ClientIPAddr, RTP_dest_port);
		
                RTPsocket.send(senddp);

                System.out.println("Send frame #" + imagenb + ", Frame size: " + image_length + " (" + buf.length + ")");
                //print the header bitstream
                rtp_packet.printheader();

                //update GUI
                label.setText("Send frame #" + imagenb);
            }
            catch(Exception ex) {
                System.out.println("Exception caught5: "+ex);
                //System.exit(0);
            }
        }
        else {
            //if we have reached the end of the video file, stop the timer
            timer.stop();
            rtcpReceiver.stopRcv();
        }
    }

//    //------------------------
//    //Controls RTP sending rate based on traffic
//    //------------------------
//    class CongestionController implements ActionListener {
//        private Timer ccTimer;
//        int interval;   //interval to check traffic stats
//        int prevLevel;  //previously sampled congestion level
//
//        public CongestionController(int interval) {
//            this.interval = interval;
//            ccTimer = new Timer(interval, this);
//            ccTimer.start();
//        }
//
//        public void actionPerformed(ActionEvent e) {
//            //adjust the send rate
//            if (prevLevel != congestionLevel) {
//                sendDelay = FRAME_PERIOD + congestionLevel * (int)(FRAME_PERIOD * 0.1);
//                timer.setDelay(sendDelay);
//                prevLevel = congestionLevel;
//                System.out.println("Send delay changed to: " + sendDelay);
//            }
//        }
//    }

    //------------------------
    //Listener for RTCP packets sent from client
    //------------------------
    class RtcpReceiver implements ActionListener {
        private Timer rtcpTimer;
        private byte[] rtcpBuf;
        int interval;

        public RtcpReceiver(int interval) {
            //set timer with interval for receiving packets
            this.interval = interval;
            rtcpTimer = new Timer(interval, this);
            rtcpTimer.setInitialDelay(0);
            rtcpTimer.setCoalesce(true);

            //allocate buffer for receiving RTCP packets
            rtcpBuf = new byte[512];
        }

        public void actionPerformed(ActionEvent e) {
            //Construct a DatagramPacket to receive data from the UDP socket
            DatagramPacket dp = new DatagramPacket(rtcpBuf, rtcpBuf.length);
            float fractionLost;

            try {
                RTCPsocket.setSoTimeout(3000);
                RTCPsocket.receive(dp);   // Blocking
                RTCPpacket rtcpPkt = new RTCPpacket(dp.getData(), dp.getLength());
                System.out.println("[RTCP] " + rtcpPkt);

                //set congestion level between 0 to 4
                fractionLost = rtcpPkt.fractionLost;
                if (fractionLost >= 0 && fractionLost <= 0.01) {
                    congestionLevel = 0;    //less than 0.01 assume negligible
                }
                else if (fractionLost > 0.01 && fractionLost <= 0.25) {
                    congestionLevel = 1;
                }
                else if (fractionLost > 0.25 && fractionLost <= 0.5) {
                    congestionLevel = 2;
                }
                else if (fractionLost > 0.5 && fractionLost <= 0.75) {
                    congestionLevel = 3;
                }
                else {
                    congestionLevel = 4;
                }
            }
            catch (InterruptedIOException iioe) {
                System.out.println("Nothing to read");
            }
            catch (IOException ioe) {
                System.out.println("Exception caught6: "+ioe);
            }
        }

        public void startRcv() {
            rtcpTimer.start();
        }

        public void stopRcv() {
            System.out.println("5555");
            if(check_wifi().equals("off/an")) {
                System.out.println("!!!!!!!");
                save_video();
            }
            rtcpTimer.stop();
        }
    }



    //------------------------------------
    //Parse RTSP Request
    //------------------------------------
    public int parseRequest(BufferedReader RTSPBufferedReader) {
        int request_type = -1;
        try { 
            //parse request line and extract the request_type:
            String RequestLine = RTSPBufferedReader.readLine();
            System.out.println("RTSP Server - Received from Client:");
            System.out.println(RequestLine);

            StringTokenizer tokens = new StringTokenizer(RequestLine);
            String request_type_string = tokens.nextToken();

            //convert to request_type structure:
            if ((new String(request_type_string)).compareTo("SETUP") == 0)
                request_type = SETUP;
            else if ((new String(request_type_string)).compareTo("PLAY") == 0)
                request_type = PLAY;
            else if ((new String(request_type_string)).compareTo("PAUSE") == 0)
                request_type = PAUSE;
            else if ((new String(request_type_string)).compareTo("TEARDOWN") == 0)
                request_type = TEARDOWN;
            else if ((new String(request_type_string)).compareTo("DESCRIBE") == 0)
                request_type = DESCRIBE;

            if (request_type == SETUP) {
                //extract VideoFileName from RequestLine
            }

            //parse the SeqNumLine and extract CSeq field
            String SeqNumLine = RTSPBufferedReader.readLine();
            System.out.println(SeqNumLine);
            tokens = new StringTokenizer(SeqNumLine);
            tokens.nextToken();
            RTSPSeqNb = Integer.parseInt(tokens.nextToken());
        
            //get LastLine
            String LastLine = RTSPBufferedReader.readLine();
            System.out.println(LastLine);

            tokens = new StringTokenizer(LastLine);
            if (request_type == SETUP) {
                //extract RTP_dest_port from LastLine
                for (int i=0; i<3; i++)
                    tokens.nextToken(); //skip unused stuff
                RTP_dest_port = Integer.parseInt(tokens.nextToken());
            }
            else if (request_type == DESCRIBE) {
                tokens.nextToken();
                String describeDataType = tokens.nextToken();
            }
            else {
                //otherwise LastLine will be the SessionId line
                tokens.nextToken(); //skip Session:
                RTSPid = tokens.nextToken();
            }
        } catch(Exception ex) {
            System.out.println("Exception caught2: "+ex);
            System.exit(0);
        }
      
        return(request_type);
    }

    // Creates a DESCRIBE response string in SDP format for current media
    private String describe() {
        StringWriter writer1 = new StringWriter();
        StringWriter writer2 = new StringWriter();
        
        // Write the body first so we can get the size later
        writer2.write("v=0" + CRLF);
        writer2.write("m=video " + RTSP_dest_port + " RTP/AVP " + MJPEG_TYPE + CRLF);
        writer2.write("a=control:streamid=" + RTSPid + CRLF);
        writer2.write("a=mimetype:string;\"video/MJPEG\"" + CRLF);
        String body = writer2.toString();

//        writer1.write("Content-Base: " + VideoFileName + CRLF);
        writer1.write("Content-Type: " + "application/sdp" + CRLF);
        writer1.write("Content-Length: " + body.length() + CRLF);
        writer1.write(body);
        
        return writer1.toString();
    }

    //------------------------------------
    //Send RTSP Response
    //------------------------------------
    public void sendResponse(BufferedWriter RTSPBufferedWriter) {
        try {
            RTSPBufferedWriter.write("RTSP/1.0 200 OK"+CRLF);
            RTSPBufferedWriter.write("CSeq: "+RTSPSeqNb+CRLF);
            RTSPBufferedWriter.write("Session: "+RTSPid+CRLF);
            RTSPBufferedWriter.flush();
            System.out.println("RTSP Server - Sent response to Client.");
        } catch(Exception ex) {
            System.out.println("Exception caught3: "+ex);
            System.exit(0);
        }
    }

    public void sendDescribe(BufferedWriter RTSPBufferedWriter) {
        String des = describe();
        try {
            RTSPBufferedWriter.write("RTSP/1.0 200 OK"+CRLF);
            RTSPBufferedWriter.write("CSeq: "+RTSPSeqNb+CRLF);
            RTSPBufferedWriter.write(des);
            RTSPBufferedWriter.flush();
            System.out.println("RTSP Server - Sent response to Client.");
        } catch(Exception ex) {
            System.out.println("Exception caught4: "+ex);
            System.exit(0);
        }
    }
}