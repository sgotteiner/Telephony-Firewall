package rtp;

import audio.AESencryption;
import audio.AudioCalculator;
import sip.Client;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class RTPHandler {

    private RTPSender sender;
    private RTPReciever receiver;
    private Timer timer;
    private boolean isServer;
    private IProxyToRTPCallBack iProxyToRTPCallBack;

    public RTPHandler(String ip, int sendPort, DatagramSocket receiveSocket, boolean isServer, IProxyToRTPCallBack iProxyToRTPCallBack) {

        System.out.println("handler created: receives at " + receiveSocket.getLocalPort() + ", sends to " + sendPort);
        this.sender = new RTPSender(ip, sendPort);
        this.receiver = new RTPReciever(receiveSocket);
        this.isServer = isServer;

        if (isServer) {
            this.iProxyToRTPCallBack = iProxyToRTPCallBack;
            transfer(ip, sendPort);
        }
    }

    private void transfer(String ip, int sendPort){
        byte[] buf = new byte[Client.FRAME_SIZE + AESencryption.ADDITION_SIZE + RTPpacket.HEADER_SIZE],
                emptyArray = new byte[1];
        DatagramPacket datagramPacket = new DatagramPacket(buf, buf.length);
        AudioCalculator audioCalculator = new AudioCalculator();
        final int[] badFrequencyCounter = { 0 }, badVolumeCounter = { 0 };
        final double[] frequency = new double[1], volume = new double[1];
        final boolean[] isSend = {true};
        timer = new Timer(100, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // get packet
                receiver.receive(datagramPacket);
                RTPpacket rtpPacket = new RTPpacket(datagramPacket.getData(), datagramPacket.getLength());
                int payloadLength = rtpPacket.getpayload_length();
                byte[] payload = new byte[payloadLength];
                rtpPacket.getpayload(payload);

                // decrypt the audio
                byte[] decArr = null;
                try {
                    System.out.println("packet: " + datagramPacket.getLength() + " payload: " + payloadLength + " payload[0]: " + payload[0]);
                    decArr = AESencryption.decrypt(payload);
                } catch (Exception exception) {
                    exception.printStackTrace();
                }

                // analyze the audio
                audioCalculator.setBytes(decArr, decArr.length);
                frequency[0] = audioCalculator.getPrinstonFrequency();
                // I send an empty array from one client just to show that both clients are sending and receiving
                // and it's frequency is obviously 0 but I don't want to hang up because I wouldn't be able to see the real conversation
                if(frequency[0] != 0) {
                    volume[0] = audioCalculator.getDecibel();
                    System.out.println("Frequency: " + frequency[0] + ", Decibel: " + volume[0]);
                    //check if the audio can be heard
                    if (frequency[0] < 100 || frequency[0] > 20000) {
                        badFrequencyCounter[0]++;
                        isSend[0] = false;
                    } else {
                        badFrequencyCounter[0] = 0;
                    }
                    if (volume[0] < 34) {
                        badVolumeCounter[0]++;
                        isSend[0] = false;
                    } else {
                        badVolumeCounter[0] = 0;
                    }
                }

                // send the packet if it is not quiet
                if(isSend[0])
                    sender.send(buf, buf.length, false);
                else {
                    emptyArray[0] = (byte) rtpPacket.getsequencenumber();
                    sender.send(emptyArray, 1, false);
                    // next packet might not be quiet
                    isSend[0] = true;
                }

                //stop the call if this is a silent call
                if(badFrequencyCounter[0] == 51 || badVolumeCounter[0] == 51)
                    iProxyToRTPCallBack.stopCall(ip + ":" + sendPort);
            }
        });
        timer.start();
    }

    public RTPSender getSender() {
        if (isServer)
            timer.stop();
        return sender;
    }

    public RTPReciever getReceiver() {
        return receiver;
    }

    public void closeAll() {
        if (isServer)
            timer.stop();
        sender.close();
        receiver.close();

    }

    public interface IProxyToRTPCallBack {
        public void stopCall(String sendAddress);
    }
}