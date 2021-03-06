package audio;

import rtp.RTPReciever;
import rtp.RTPSender;
import rtp.RTPpacket;
import sip.Client;

import javax.sound.sampled.*;
import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

public class testVoice {

    private static AudioStream audio = null;
    private static byte[] buf, emptyArray, encArr, decArr;
    private static DatagramPacket datagramPacket;
    private static SourceDataLine speaker;
    private static boolean flag = true;
    private static int badFrequencyCounter = 0, badVolumeCounter = 0;
    private static boolean isSend = true;
    private static final boolean isCalculateFrequencyAndVolume = false, isEncryption = false;

    public static void main(String[] args) {

        RTPSender rtpSender = new RTPSender("localhost", 10000);
        DatagramSocket datagramSocket = null;
        try {
            datagramSocket = new DatagramSocket(10000);
        } catch (SocketException e) {
            e.printStackTrace();
        }
        RTPReciever rtpReciever = new RTPReciever(datagramSocket);

        buf = new byte[Client.FRAME_SIZE];
        emptyArray = new byte[1];
        encArr = null;
        decArr = null;
        int size = Client.FRAME_SIZE + RTPpacket.HEADER_SIZE;
        size += isEncryption ? AESencryption.ADDITION_SIZE : 0;
        datagramPacket = new DatagramPacket(new byte[size], size);

        try {
            audio = new AudioStream();
        } catch (Exception e) {
            e.printStackTrace();
        }

        AudioFormat format = new AudioFormat(8000, 16, 1, true, true);
        DataLine.Info dataLineInfo = new DataLine.Info(SourceDataLine.class, format);
        try {
            speaker = (SourceDataLine) AudioSystem.getLine(dataLineInfo);
            speaker.open(format);
        } catch (LineUnavailableException e) {
            e.printStackTrace();
        }
        speaker.start();

        AudioCalculator audioCalculator = new AudioCalculator();


        Timer sendTimer = new Timer(100, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                //get next frame to send from the video, as well as its size
                int audioLength = 0;
                try {
                    audioLength = audio.getNextFrame(buf);
                    if(isCalculateFrequencyAndVolume) {
                        audioCalculator.setBytes(buf, audioLength);
                        double decibel = audioCalculator.getDecibel();
                        double frequency = audioCalculator.getPrinstonFrequency();
                        System.out.println("Frequency: " + frequency + ", Decibel: " + decibel);
                        //check if the audio can be heard
                        if (frequency < 100 || frequency > 20000) {
                            badFrequencyCounter++;
                            isSend = false;
                        } else {
                            badFrequencyCounter = 0;
                        }
                        if (decibel < 30) {
                            badVolumeCounter++;
                            isSend = false;
                        } else {
                            badVolumeCounter = 0;
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                if (isSend){
                    if(isEncryption) {
                        try {
                            encArr = AESencryption.encrypt(buf);
                            rtpSender.send(encArr, encArr.length, true);
                        } catch (Exception exception) {
                            exception.printStackTrace();
                        }
                    } else {
                        rtpSender.send(buf, buf.length, true);
                    }
                }
                else rtpSender.send(emptyArray, 1, false);
                isSend = true;
            }
        });

        Timer receiveTimer = new Timer(100, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {

                speaker.flush();

                rtpReciever.receive(datagramPacket);

                //quiet audio
                if (datagramPacket.getLength() == 1){
                    System.out.println("quiet");
                    return;
                }

                //create an rtp.RTPpacket object from the DP
                rtp.RTPpacket rtpPacket = new rtp.RTPpacket(datagramPacket.getData(), datagramPacket.getLength());
                int seqNumber = rtpPacket.getsequencenumber();
                //this is the highest seq num received

                //print important header fields of the RTP packet received:
                System.out.println("Got RTP packet with SeqNum # " + seqNumber
                        + " TimeStamp " + rtpPacket.gettimestamp() + " ms, of type "
                        + rtpPacket.getpayloadtype());

                //print header bitstream:
                rtpPacket.printheader();

                //get the payload bitstream from the rtp.RTPpacket object
                int payloadLength = rtpPacket.getpayload_length();
                byte[] payload = new byte[payloadLength];
                rtpPacket.getpayload(payload);

                if(isEncryption) {
                    try {
                        decArr = AESencryption.decrypt(payload);
                        speaker.write(decArr, 0, decArr.length);
                    } catch (Exception exception) {
                        exception.printStackTrace();
                    }
                } else {
                    speaker.write(payload, 0, payloadLength);
                }

                if (seqNumber == 150)
                    flag = false;
            }
        });

        while (flag && badFrequencyCounter < 51 && badVolumeCounter < 51) {
            sendTimer.start();

            receiveTimer.start();
        }
        System.out.println("bad frequency: " + badFrequencyCounter + " bad volume: " + badVolumeCounter);

        receiveTimer.stop();
        rtpReciever.close();
        sendTimer.stop();
        rtpSender.close();
        audio.closeMic();
    }
}