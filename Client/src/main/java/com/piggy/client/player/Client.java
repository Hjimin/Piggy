/* ------------------
   Client
   usage: java Client [Server hostname] [Server RTSP listening port] [Video file requested]
   ---------------------- */
package com.piggy.client.player;
import java.awt.event.*;
import javax.swing.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.PipedOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.text.DecimalFormat;
import java.util.Random;
import java.util.StringTokenizer;

public class Client extends JPanel implements Runnable {

    boolean wifi_restart_flag = false;
    //RTP variables:
    //----------------
    DatagramPacket rcvdp;            //UDP packet received from the server
    DatagramSocket RTPsocket;        //socket to be used to send and receive UDP packets
    static int RTP_RCV_PORT = 5004; //port where the client will receive the RTP packets
    
//    File outputFile = new File("sample.h264");
//    FileOutputStream fos;
    Timer timer; //timer used to receive data from the UDP socket
    byte[] buf;  //buffer used to store data received from the server 
   
    //RTSP variables
    //----------------
    //rtsp states 
    final static int INIT = 0;
    final static int READY = 1;
    final static int PLAYING = 2;
    static int state;            //RTSP state == INIT or READY or PLAYING
    Socket RTSPsocket;           //socket used to send/receive RTSP messages
    InetAddress ServerIPAddr;

    //input and output stream filters
    static BufferedReader RTSPBufferedReader;
    static BufferedWriter RTSPBufferedWriter;
    static String VideoFileName; //video file to request to the server
    int RTSPSeqNb = 0;           //Sequence number of RTSP messages within the session
    String RTSPid;              // ID of the RTSP session (given by the RTSP Server)

    final static String CRLF = "\r\n";
    final static String DES_FNAME = "session_info.txt";

    //RTCP variables
    //----------------
    DatagramSocket RTCPsocket;          //UDP socket for sending RTCP packets
    static int RTCP_RCV_PORT = 19001;   //port where the client will receive the RTP packets
    static int RTCP_PERIOD = 400;       //How often to send RTCP packets
    RtcpSender rtcpSender;

    //Video constants:
    //------------------
    static int MJPEG_TYPE = 96; //RTP payload type for MJPEG video

    //Statistics variables:
    //------------------
    double statDataRate;        //Rate of video data received in bytes/s
    int statTotalBytes;         //Total number of bytes received in a session
    double statStartTime;       //Time in milliseconds when start is pressed
    double statTotalPlayTime;   //Time in milliseconds of video playing since beginning
    float statFractionLost;     //Fraction of RTP data packets from sender lost since the prev packet was sent
    int statCumLost;            //Number of packets lost
    int statExpRtpNb;           //Expected Sequence number of RTP messages within the session
    int statHighSeqNb;          //Highest sequence number received in session

    PipedOutputStream pipedOutputStream;
    int wifi_check_cnt = 0;
    H264Player h264Player;
    boolean reconnect_flag = false;
    View v;

    SharedArea sharedArea;
    //--------------------------
    //Constructor
    //--------------------------
    public Client(PipedOutputStream pipedOutputStream, View v, H264Player h264Player, SharedArea sharedArea ) {
        //share Thread data
        this.pipedOutputStream = pipedOutputStream;
        this.h264Player  = h264Player;
        this.sharedArea = sharedArea;
        this.v = v;
        v.setupButton.addActionListener(new setupButtonListener());
        v.playButton.addActionListener(new playButtonListener());
        v.pauseButton.addActionListener(new pauseButtonListener());
        v.exitButton.addActionListener(new exitButtonListener());
        v.saveButton.addActionListener(new saveButtonListener());

        //init timer
        timer = new Timer(50, new timerListener());
        timer.setInitialDelay(0);
        timer.setCoalesce(true);

        //init RTCP packet sender
        rtcpSender = new RtcpSender(400);

        //allocate enough memory for the buffer used to receive data from the server
        buf = new byte[25000];    

    }
    
    public void run() {
        //Run Client Thread
        try {
            //Fixme:// change so it can get port and Ip when exec.
            //get server RTSP port and IP address from the command line
            int RTSP_server_port = 1052;//Integer.parseInt(argv[1]);
            String ServerHost = "192.168.0.248";//"203.252.160.76";//"192.168.0.11";//argv[0];
            ServerIPAddr = InetAddress.getByName(ServerHost);

            //Establish a TCP connection with the server to exchange RTSP messages
            RTSPsocket = new Socket(ServerIPAddr, RTSP_server_port);

            //Establish a UDP connection with the server to exchange RTCP control packets
            //Set input and output stream filters:
            RTSPBufferedReader = new BufferedReader(new InputStreamReader(RTSPsocket.getInputStream()));
            RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(RTSPsocket.getOutputStream()));

            //init RTSP state:
            state = INIT;
        } catch (Exception e) {
            System.out.println(e.toString());
        }
    }
    //------------------------------------
    //Handler for buttons
    //------------------------------------
    //Handler for Setup button
    class setupButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e){
        	System.out.println("Setup Button pressed !");
            if (state == INIT) {
                //Init non-blocking RTPsocket that will be used to receive data
                try {
                    //construct a new DatagramSocket to receive RTP packets from the server, on port RTP_RCV_PORT
                    RTPsocket = new DatagramSocket(RTP_RCV_PORT);
                    //UDP socket for sending QoS RTCP packets
                    RTCPsocket = new DatagramSocket();
                    //set TimeOut value of the socket to 5msec.
                    RTPsocket.setSoTimeout(1000);
                }
                catch (SocketException se)
                {
                    System.out.println("Socket exception: "+se);
                    System.exit(0);
                }
                //init RTSP sequence number
                RTSPSeqNb = 1;
                //Send SETUP message to the server
                sendRequest("SETUP");
                //Wait for the response 
                if (parseServerResponse() != 200)
                    System.out.println("Invalid Server Response");
                else {
                    //change RTSP state and print new state 
                    state = READY;
                    System.out.println("New RTSP state: READY");
                }
            }
            //else if state != INIT then do nothing
        }
    }
    class saveButtonListener implements ActionListener{
    	public void actionPerformed(ActionEvent e) {
    		 String[] selectItems = v.right.getItems();
             for(int i=0;i<selectItems.length;i++) {
                 v.downloadList+=selectItems[i]+"#";
                 v.left.add(selectItems[i]);
             }
             v.right.removeAll();
             System.out.println("download list : "+v.downloadList);
             System.out.println("Sending DOWNLOAD request");
             //increase RTSP sequence number
             RTSPSeqNb++;

             //Send DESCRIBE message to the server
             sharedArea.downloadList = v.downloadList;
             sharedArea.file_flag = true;
             sendRequest("DOWNLOAD");
             v.addLog("Client sent download request to Server");
             System.out.println("Thread started!!!!!!!!!!!!!");
             
             //Wait for the response
             if (parseServerResponse() != 200) {
                 System.out.println("Invalid Server Response");
             }
             else {
                 System.out.println("Receiving file data from server");
             }
    	}
    }
    //Handler for Play button
    //-----------------------
    class playButtonListener implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            //Start to save the time in stats
            statStartTime = System.currentTimeMillis();
            if (state == READY) {
                //increase RTSP sequence number
                RTSPSeqNb++;
                //Send PLAY message to the server
                sendRequest("PLAY");
                //Wait for the response 
                if (parseServerResponse() != 200) {
                    System.out.println("Invalid Server Response");
                    v.addLog("Error : failed playing video");
                }
                else {
                    //change RTSP state and print out new state
                    state = PLAYING;
                    System.out.println("New RTSP state: PLAYING");
                    //Fixme:// this part is not necessary it is only for test
                    //don't need to save file
                    //start the timer
                    timer.start();
                    rtcpSender.startSend();
                    v.addLog("Client started playing live video");

                }
            }
            //else if state != READY then do nothing
        }
    }
    //Handler for Pause button
    //-----------------------
    class pauseButtonListener implements ActionListener {
        public void actionPerformed(ActionEvent e){
            if (state == PLAYING) 
            {
                //increase RTSP sequence number
                RTSPSeqNb++;
                //Send PAUSE message to the server
                sendRequest("PAUSE");
                //Wait for the response 
                if (parseServerResponse() != 200) {
                    System.out.println("Invalid Server Response");
                	v.addLog("Error : couldn't stop video");
                }
                else 
                {
                    //change RTSP state and print out new state
                    state = READY;
                    System.out.println("New RTSP state: READY");
                      
                    //stop the timer
                    timer.stop();
                    rtcpSender.stopSend();
                    try {
                        pipedOutputStream.flush();
                    } catch (Exception e1) {
                        System.out.println(e1.toString());
                    }
                    h264Player.replay();
                    v.addLog("Video paused");

                }
                //Fixme:// need to change, we don't need file anymore
                //Fixme:// need to stop h264Player too.
            }
            //else if state != PLAYING then do nothing
        }
    }
    //Handler for exitdown button
    //-----------------------
    class exitButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e){
            //increase RTSP sequence number
            RTSPSeqNb++;

            //Send exitDOWN message to the server
            sendRequest("TEARDOWN");

            //Wait for the response 
            if (parseServerResponse() != 200)
                System.out.println("Invalid Server Response");
            else {     
                //change RTSP state and print out new state
                state = INIT;
                System.out.println("New RTSP state: INIT");
                //stop the timer
                timer.stop();
                rtcpSender.stopSend();

                //exit
                System.exit(0);
            }
        }
    }

    //------------------------------------
    //Handler for timer
    //------------------------------------
    class timerListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {
            //Construct a DatagramPacket to receive data from the UDP socket
            rcvdp = new DatagramPacket(buf, buf.length);

            //Fixme: need to erase this part of the code and test wifi disconnection
            try {
                //receive the DP from the socket, save time for stats
                try {
                    RTPsocket.receive(rcvdp);
                } catch (Exception e5) {
                    System.out.println(e5.toString());
                }

                if(reconnect_flag && rcvdp != null) {
                    reconnect_flag = false;
                    sendRequest("FILELIST");
                    int option = parseServerResponse();
                    System.out.println(option);
                    if (option == 1234) {
                        // Jimin_Here
                        System.out.println("FileLIST COMEON!!!!!!!!!!!!");
                    }
                    else {
                        System.out.println("Invalid Server Response");
                    }
                }

                double curTime = System.currentTimeMillis();
                statTotalPlayTime += curTime - statStartTime;
                statStartTime = curTime;

                //create an RTPpacket object from the DP
                RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());
                int seqNb = rtp_packet.getsequencenumber();

                //this is the highest seq num received

                //print important header fields of the RTP packet received:
                System.out.println("Got RTP packet with SeqNum # " + seqNb
                        + " TimeStamp " + rtp_packet.gettimestamp() + " ms, of type "
                        + rtp_packet.getpayloadtype());

                //print header bitstream:
                rtp_packet.printheader();

                //get the payload bitstream from the RTPpacket object
                int payload_length = rtp_packet.getpayload_length();
                if(payload_length > 0) {

                    byte[] payload = new byte[payload_length];
                    rtp_packet.getpayload(payload);


                    if (wifi_check_cnt == 20) {
                        sendRequest("WIFI");
                        int option = parseServerResponse();
                        if (option == 400) {
                            System.out.println("New RTSP state: WIFI CHANGED");
                            try {
                                pipedOutputStream.flush();
                            } catch (IOException e1) {
                                System.out.println("Exception Jimin 3 " + e1.toString());
                            }
                            wifi_restart_flag = true;
                        } else if (option == 300) {
                            System.out.println("New RTSP state: WIFI NOT CHANGED");
                        } else if(option == 500) {
                            System.out.println("New RTSP state: WIFI Reconnected");
                            reconnect_flag = true;
                        }
                        else {
                            System.out.println("Invalid Server Response");
                        }
                        wifi_check_cnt = 0;
                    }
                    wifi_check_cnt++;
                    try {
                        pipedOutputStream.write(payload);
                    } catch (IOException e1) {
                        System.out.println("Exception caught Jimin 2" + e1.toString());
                    }
                    if(wifi_restart_flag) {
                        h264Player.replay();
                        wifi_restart_flag = false;
                    }
                    //compute stats and update the label in GUI
                    statExpRtpNb++;
                    if (seqNb > statHighSeqNb) {
                        statHighSeqNb = seqNb;
                    }
                    if (statExpRtpNb != seqNb) {
                        statCumLost++;
                    }
                    statDataRate = statTotalPlayTime == 0 ? 0 : (statTotalBytes / (statTotalPlayTime / 1000.0));
                    statFractionLost = (float) statCumLost / statHighSeqNb;
                    statTotalBytes += payload_length;
                    updateStatsLabel();
                } else {
                    System.out.println("*************************************");
                }
            } catch (Exception ioe) {
                System.out.println("Exception caught: Jimin 1 " + ioe.toString());
            }
        }
    }
    //------------------------------------
    // Send RTCP control packets for QoS feedback
    //------------------------------------
    class RtcpSender implements ActionListener {

        private Timer rtcpTimer;
        int interval;
        // Stats variables
        private int numPktsExpected;    // Number of RTP packets expected since the last RTCP packet
        private int numPktsLost;        // Number of RTP packets lost since the last RTCP packet
        private int lastHighSeqNb;      // The last highest Seq number received
        private int lastCumLost;        // The last cumulative packets lost
        private float lastFractionLost; // The last fraction lost
        Random randomGenerator;         // For testing only

        public RtcpSender(int interval) {
            this.interval = interval;
            rtcpTimer = new Timer(interval, this);
            rtcpTimer.setInitialDelay(0);
            rtcpTimer.setCoalesce(true);
            randomGenerator = new Random();
        }
        public void run() {
            System.out.println("RtcpSender Thread Running");
        }
        public void actionPerformed(ActionEvent e) {

            // Calculate the stats for this period
            numPktsExpected = statHighSeqNb - lastHighSeqNb;
            numPktsLost = statCumLost - lastCumLost;
            lastFractionLost = numPktsExpected == 0 ? 0f : (float)numPktsLost / numPktsExpected;
            lastHighSeqNb = statHighSeqNb;
            lastCumLost = statCumLost;

            //To test lost feedback on lost packets
            // lastFractionLost = randomGenerator.nextInt(10)/10.0f;

            RTCPpacket rtcp_packet = new RTCPpacket(lastFractionLost, statCumLost, statHighSeqNb);
            int packet_length = rtcp_packet.getlength();
            byte[] packet_bits = new byte[packet_length];
            rtcp_packet.getpacket(packet_bits);

            try {
                DatagramPacket dp = new DatagramPacket(packet_bits, packet_length, ServerIPAddr, RTCP_RCV_PORT);
                RTCPsocket.send(dp);
            } catch (InterruptedIOException iioe) {
                System.out.println("1 Nothing to read");
            } catch (IOException ioe) {
                System.out.println("Exception caught: "+ioe);
            }
        }

        // Start sending RTCP packets
        public void startSend() {
            rtcpTimer.start();
        }

        // Stop sending RTCP packets
        public void stopSend() {
            rtcpTimer.stop();
        }
    }

    //------------------------------------
    //Synchronize frames
    //------------------------------------
    //------------------------------------
    //Parse Server Response
    //------------------------------------
    private int parseServerResponse() {
        int reply_code = 0;

        try {
            //parse status line and extract the reply_code:
            String StatusLine = RTSPBufferedReader.readLine();
            System.out.println("RTSP Client - Received from Server:");
            System.out.println(StatusLine);
          
            StringTokenizer tokens = new StringTokenizer(StatusLine);
            tokens.nextToken(); //skip over the RTSP von
            reply_code = Integer.parseInt(tokens.nextToken());

            System.out.println("reply_code: " + reply_code);
            //if reply code is OK get and print the 2 other lines
            if (reply_code == 200) {
                String SeqNumLine = RTSPBufferedReader.readLine();
                System.out.println(SeqNumLine);
                
                String SessionLine = RTSPBufferedReader.readLine();
                System.out.println(SessionLine);

                tokens = new StringTokenizer(SessionLine);
                String temp = tokens.nextToken();
                //if state == INIT gets the Session Id from the SessionLine
                if (state == INIT && temp.compareTo("Session:") == 0) {
                    RTSPid = tokens.nextToken();
                }
                else if (temp.compareTo("Content-Base:") == 0) {
                    // Get the DESCRIBE lines
                    String newLine;
                    for (int i = 0; i < 6; i++) {
                        newLine = RTSPBufferedReader.readLine();
                        System.out.println(newLine);
                    }
                }
            } else if (reply_code == 1234) {
                String fileList = RTSPBufferedReader.readLine();
                System.out.println("!!!!!!!!Filelist: " + fileList);
                for(String str : fileList.split("#")) {
                    if(str.length() > 0) {
                        v.left.add(str);
                    }
                }
            }
        } catch(Exception ex) {
            System.out.println("Exception caught 9 : "+ex);
            System.exit(0);
        }

        return(reply_code);
    }

    private void updateStatsLabel() {
        DecimalFormat formatter = new DecimalFormat("###,###.##");
        v.statLabel1.setText("Total Bytes Received: " + statTotalBytes);
        v.statLabel2.setText("Packet Lost Rate: " + formatter.format(statFractionLost));
        v.statLabel3.setText("Data Rate: " + formatter.format(statDataRate) + " bytes/s");
    }
    //------------------------------------
    //Send RTSP Request
    //------------------------------------
    private void sendRequest(String request_type) {
        try {
            //Use the RTSPBufferedWriter to write to the RTSP socket

            //write the request line:
            RTSPBufferedWriter.write(request_type + " " + VideoFileName + " RTSP/1.0" + CRLF);

            //write the CSeq line: 
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);

            //check if request_type is equal to "SETUP" and in this case write the 
            //Transport: line advertising to the server the port used to receive 
            //the RTP packets RTP_RCV_PORT
            if (request_type == "SETUP") {
                RTSPBufferedWriter.write("Transport: RTP/UDP; client_port= " + RTP_RCV_PORT + CRLF);
            }
            else if (request_type == "WIFI") {
                RTSPBufferedWriter.write("Check: WIFI" + CRLF);
            }
            else if (request_type == "DOWNLOAD") {
                RTSPBufferedWriter.write("DOWNLOAD: " + v.downloadList + CRLF); //#########
            }
            else {
                //otherwise, write the Session line from the RTSPid field
                RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
            }
            RTSPBufferedWriter.flush();
        } catch(Exception ex) {
            System.out.println("Exception caught: "+ex);
            System.exit(0);
        }
    }
}
