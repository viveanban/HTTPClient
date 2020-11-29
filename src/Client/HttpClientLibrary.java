package Client;

import Client.Requests.PostRequest;
import Client.Requests.Redirectable;
import Client.Requests.Request;
import Helpers.Packet;
import Helpers.PacketType;
import Helpers.Status;
import Helpers.UDPConnection;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This class is the client library. It takes care of opening the TCP connection, sending the request and reading the response.
 */
public class HttpClientLibrary {

    private DatagramSocket clientSocket;
    private Request request;
    private boolean isVerbose;
    private String responseFilePath;
    private int redirectCounter = 0;
    private final static int REDIRECT_MAXIMUM = 5;
    private BufferedWriter writer;
    private final static String EOL = "\r\n";

    private static final Logger logger = Logger.getLogger(HttpClientLibrary.class.getName());

    private static int windowHead = 0;
    private static int windowTail = UDPConnection.WINDOW_SIZE - 1;
    private static ArrayList<Boolean> ackList;
    private static ArrayList<Boolean> sentList;


    public HttpClientLibrary(Request request, boolean isVerbose) {
        this(request, isVerbose, "");
    }

    public HttpClientLibrary(Request request, boolean isVerbose, String responseFilePath) {
        this.request = request;
        this.isVerbose = isVerbose;
        this.responseFilePath = responseFilePath;
        try {
            if (!responseFilePath.isEmpty())
                writer = new BufferedWriter(new FileWriter(responseFilePath));
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(0);
        }
        performRequest();
    }

    private void performRequest() {
        try {
            clientSocket = new DatagramSocket();
            handshake();
            sendRequest();
//            readResponse();
        } catch (IOException exception) {
            exception.printStackTrace();
        } finally {
            closeUDPConnection();
            System.exit(0);
        }
    }

    private void handshake() throws IOException {
        int initialSequenceNumber = UDPConnection.getRandomSequenceNumber();

        // Send SYN
        logger.info("Initiate 3-way handshake ...");
        logger.info("Send SYN packet with seq number " + initialSequenceNumber);
        UDPConnection.sendSYN(initialSequenceNumber, request.getPort(), request.getAddress(), clientSocket);

        // Receive SYN_ACK
        Packet packet = receiveAndVerifySYN_ACK(initialSequenceNumber);

        // Send ACK
        logger.info("Respond with an ACK {ACK:" + (packet.getSequenceNumber() + 1) + "}");
        UDPConnection.sendACK(packet.getSequenceNumber() + 1, packet.getPeerPort(), packet.getPeerAddress(), clientSocket);
    }

    private Packet receiveAndVerifySYN_ACK(int initialSequenceNumber) throws IOException {
        Packet packet = UDPConnection.receivePacket(clientSocket);

        UDPConnection.verifyPacketType(PacketType.SYN_ACK, packet, clientSocket);
        logger.info("Received a SYN_ACK packet");

        logger.info("Verifying ACK ...");
        int receivedAcknowledgment = getIntFromPayload(packet.getPayload());
        if (receivedAcknowledgment != initialSequenceNumber + 1) {
            logger.info("Unexpected ACK sequence number " + receivedAcknowledgment + "instead of " + (initialSequenceNumber + 1));
            UDPConnection.sendNAK(packet.getPeerPort(), packet.getPeerAddress(), clientSocket);
            System.exit(-1);
        }

        logger.info("ACK is verified: {seq sent: " + initialSequenceNumber + ", seq received: " + receivedAcknowledgment);
        return packet;
    }

    private void sendRequest() throws IOException {
        // Build payload
        String payload = constructPayload();

        // Build packets
        ArrayList<Packet> packets = UDPConnection.buildPackets(payload, PacketType.DATA, request.getPort(), request.getAddress()); // TODO: how to use NAK?

        // Send packets using selective repeat
        sendRequestUsingSelectiveRepeat(packets);
    }


    private void sendRequestUsingSelectiveRepeat(ArrayList<Packet> packets) {
        // Set up
        ackList = new ArrayList<>(Arrays.asList(new Boolean[packets.size()]));
        sentList = new ArrayList<>(Arrays.asList(new Boolean[packets.size()]));
        Collections.fill(ackList, Boolean.FALSE);
        Collections.fill(sentList, Boolean.FALSE);

        // Send data packets using selective repeat
        while(ackList.contains(false)) {
            sendWindow(packets);

            Packet response = UDPConnection.receivePacket(clientSocket);
            if (response != null && response.getType() == PacketType.ACK.value) {
                ackList.set((int) response.getSequenceNumber()-1, true);
                // Slide window
                if (windowTail <= ackList.size() && ackList.get(windowHead)) {
                    windowHead += 1;
                    windowTail += 1;
                }
            }
        }

        // Send FIN to let receiver know you're done sending data
        int finalSequenceNumber = UDPConnection.getRandomSequenceNumber();
        UDPConnection.sendFIN(finalSequenceNumber, request.getPort(), request.getAddress(), clientSocket);

        // Wait for receiver ACK
        receiveAndVerifyFinalACK(finalSequenceNumber);

        // Wait for receiver DATA

    }

    private void receiveAndVerifyFinalACK(int sequenceNumberToSynchronize) {
        Packet packet = UDPConnection.receivePacket(clientSocket);
        try {
            UDPConnection.verifyPacketType(PacketType.ACK, packet, clientSocket);
        } catch (IOException e) {
            e.printStackTrace();
        }

        logger.info("Received a ACK packet");
        logger.info("Verifying ACK ...");
        if (packet.getSequenceNumber() != sequenceNumberToSynchronize + 1) {
            logger.info("Unexpected ACK sequence number " + packet.getSequenceNumber() + "instead of " + (sequenceNumberToSynchronize + 1));
            UDPConnection.sendNAK(packet.getPeerPort(), packet.getPeerAddress(), clientSocket);
            System.exit(-1);
        }
        logger.info("ACK is verified: {seq sent: " + sequenceNumberToSynchronize + ", seq received: " + packet.getSequenceNumber());
    }


    private void sendWindow(ArrayList<Packet> packets) {
        for (int i = windowHead; i <= windowTail && windowTail < ackList.size(); i++) {
            if (!ackList.get(i) && !sentList.get(i)) {
                Packet packet = packets.get(i);
                UDPConnection.sendPacket(packets.get(i), clientSocket);

                // Start a timer
                Timer timer = new Timer();
                timer.schedule(new ResendPacket(packet, i), 10000);

                sentList.set(i, true);
            }
        }
    }

    private String constructPayload() {
        String requestLine = request.getMethod().name() + " " + request.getPath() + request.getQuery() + " " + "HTTP/1.0" + EOL;
        String hostHeader = "Host: " + request.getHost() + EOL;

        String headers = "";
        if (request.getHeaders().size() > 0) {
            for (String header : request.getHeaders()) {
                headers += header + EOL;
            }
        }

        String body = "";
        if (request instanceof PostRequest) {
            body = EOL +
                    ((PostRequest) request).getData() + EOL;
        }

        return requestLine + hostHeader + headers + body + EOL;
    }

    private void readResponse() {
        logger.log(Level.INFO, "Reading server's response...");
        Packet responsePacket;
        String responsePayload = "";
        try {
            byte[] buff = new byte[Packet.MAX_LEN];
            DatagramPacket datagramPacket = new DatagramPacket(buff, Packet.MAX_LEN);
            clientSocket.receive(datagramPacket);

            responsePacket = Packet.fromBytes(datagramPacket.getData());
            responsePayload = new String(responsePacket.getPayload(), UTF_8);
            logger.info("Packet: {" + responsePacket + "}");
            logger.info("Payload: {" + responsePayload + "}");

        } catch (SocketException socketException) {
            socketException.printStackTrace();
        } catch (IOException exception) {
            exception.printStackTrace();
        }

        String[] responseLines = responsePayload.split(EOL);
        int lineCounter = 0;
        try {
            // Read status line and check if it is a redirect
            String line = responseLines.length >= 1 ? responseLines[lineCounter] : null;

            boolean shouldRedirect = shouldRedirect(line);

            // Parse through response headers
            line = responseLines.length >= 2 ? responseLines[++lineCounter] : null;
            while (line != null && !line.isEmpty() && !line.startsWith("{")) {

                //Search headers for Location: redirectURI
                if (shouldRedirect && line.contains("Location:")) {
                    printLine(line); // print the location header
                    String redirectURI = line.substring(line.indexOf(":") + 1).trim();
                    redirectTo(redirectURI);
                    return;
                }

                if (isVerbose)
                    printLine(line);
                line = responseLines.length >= ++lineCounter ? responseLines[lineCounter] : null;
            }

            // There is an error if the redirect link is not in the response headers
            if (shouldRedirect) {
                System.out.println("Response code 302 but no redirection URI found!");
                System.exit(0);
            }

            // Print out response body
            while (line != null) {
                printLine(line);
                line = responseLines.length >= ++lineCounter ? responseLines[lineCounter] : null;
            }

            if (writer != null)
                writer.close();

        } catch (IOException e) {
            e.printStackTrace();
            System.exit(0);
        }
    }

    private void closeUDPConnection() {
        clientSocket.close();
    }

    private boolean shouldRedirect(String line) {
        boolean shouldRedirect = false;
        if (line != null) {
            String[] statusLineComponents = line.trim().split(" ");
            if (statusLineComponents.length >= 3) {
                if (isVerbose) printLine(line);
                try {
                    int statusCode = Integer.parseInt(statusLineComponents[1]);
                    boolean isRedirectCode = statusCode == Status.MOVED_PERMANENTLY.getCode() ||
                            statusCode == Status.FOUND.getCode() ||
                            statusCode == Status.TEMPORARY_REDIRECT.getCode();
                    shouldRedirect = isRedirectCode && request instanceof Redirectable;
                } catch (NumberFormatException exception) {
                    System.out.println("Status code cannot be converted to int: " + exception);
                    System.exit(0);
                }

            } else {
                System.out.println("Response's status line is not formatted properly: " + line);
                System.exit(0);
            }

        } else {
            System.out.println("Status line is not there!");
            System.exit(0);
        }
        return shouldRedirect;
    }

    private void redirectTo(String redirectURI) {
        // Close existing socket
        closeUDPConnection();

        if (redirectCounter < REDIRECT_MAXIMUM && request instanceof Redirectable) {
            System.out.println("------------ REDIRECTED -------------");
            this.request = ((Redirectable) request).getRedirectRequest(redirectURI);
            redirectCounter++;
            performRequest();
        }
    }

    private void writeToFile(String line) {
        try {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(0);
        }
    }

    private void printLine(String line) {
        if (writer != null)
            writeToFile(line);
        else
            System.out.println(line);
    }

    public int getIntFromPayload(byte[] payload){
        IntBuffer intBuf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).asIntBuffer();
        int[] array = new int[intBuf.remaining()];
        intBuf.get(array);
        return array[0];
    }

    private class ResendPacket extends TimerTask {
        private Packet p;
        int indexInAckList;

        public ResendPacket(Packet p, int indexInAckList) {
            this.p = p;
            this.indexInAckList = indexInAckList;
        }

        public void run() {
//            if(!ackList.get(indexInAckList))
//                UDPConnection.sendPacket(p, clientSocket);
        }
    }

}
