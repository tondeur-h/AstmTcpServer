package astmtcpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;

public class AstmTcpServer 
{
    private  int ASTM_PORT = 5000; //port ASTM en écoute
    private  String MLLP_HOST = "127.0.0.1"; // Adresse du connecteur EAI
    private  int MLLP_PORT = 6000;           // Port du connecteur EAI

    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte EOT = 0x04;
    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;
    private static final byte NAK = 0x15;
    private static final byte CR = 0x0D;
    
    private static final byte VT = 0x0B; // MLLP start block
    private static final byte FS = 0x1C; // MLLP end block

    private static boolean parseastm=false;
    private static boolean viamllp=false;
    private static boolean viafile=false;
    private static String outputDirectory="";
    
    
    
private static final Logger LOGGER = Logger.getLogger(AstmTcpServer.class.getName());
static {
try {
FileHandler fh = new FileHandler("AstmTcpServer.log", true);
fh.setFormatter(new SimpleFormatter());
LOGGER.addHandler(fh);
} catch (IOException e) {
System.err.println("Erreur d'initialisation du logger : " + e.getMessage());
}
}

    
    /**********************************
     *     MAIN ENTRY POINT
     * @param args 
     **********************************/
    public static void main(String[] args) 
    {
        if (args.length != 7) 
        {
            System.err.println("AstmTcpServer erreur : nombre d'arguments incorrect.");
            System.err.println("");
            System.err.println("AstmTcpServer version 0.1 copyright(c) IsiHop.fr");
            System.err.println("Utilisation : java AstmTcpServer <ASTM_PORT> <MLLP_PORT> <MLLP_HOST> <parseAstm> <viaMLLP> <viaFile> <outputDirectory>");
            System.err.println("Exemple : java AstmTcpServer 5000 6000 127.0.0.1 false true false c:/mondossier");
            System.err.println("");
            System.exit(0);
        }

        int astmPort = 0;
        int mllpPort = 0;
        String mllpHost = args[2];

        try 
        {
            astmPort = Integer.parseInt(args[0]);
            mllpPort = Integer.parseInt(args[1]);
        }
        catch (NumberFormatException e) 
        {
            System.err.println("Erreur : les ports ASTM et MLLP doivent être des entiers.");
            System.exit(1);
        }
        
        try 
        {
            parseastm = Boolean.parseBoolean(args[3]);
            viamllp = Boolean.parseBoolean(args[4]);
            viafile = Boolean.parseBoolean(args[5]);
            outputDirectory = args[6];

        }
        catch (NumberFormatException e) 
        {
            System.err.println("Erreur : les  valeurs parseAstm,ViaMLLP,FIleWriting doivent être des booleen true/false.");
            System.exit(1);
        }

        System.out.println("ASTM TCP Server ecoutera sur le port " + astmPort);
        if (viamllp==true) {System.out.println("Transfert des messages MLLP a " + mllpHost + ":" + mllpPort);}
        if (parseastm==true) {System.out.println("Parse message sur ecran active.");}
        if (viamllp==true) {System.out.println("Envoie vers service MLLP active.");}
        if (viafile==true) {System.out.println("Ecriture vers fichier active.");}

        
        new AstmTcpServer(astmPort,mllpPort,mllpHost);
    }
    
    /**************************************
     *  CONSTRUCTEUR
     * @param astm_port
     * @param mllp_port
     * @param mllp_host 
     **************************************/
    public AstmTcpServer(int astm_port,int mllp_port,String mllp_host) 
    {
        //ajuster les port et IP
        ASTM_PORT=astm_port;
        MLLP_PORT=mllp_port;
        MLLP_HOST=mllp_host;
        
        try (ServerSocket serverSocket = new ServerSocket(ASTM_PORT)) 
        {
            System.out.println("Serveur TCP ASTM TCP connecte sur le port " + ASTM_PORT);

            while (true) 
            {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connecte: " + clientSocket.getInetAddress());

                new Thread(() -> handleClient(clientSocket)).start();
            }

        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    /************************************
     * Traitement du message en reception
     * @param socket 
     ************************************/
    private void handleClient(Socket socket) 
    {
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) 
        {
            int b;
            boolean inFrame = false;
            StringBuilder frame = new StringBuilder();

            while ((b = in.read()) != -1) 
            {
                byte data = (byte) b;

                if (data == ENQ) 
                {
                    out.write(ACK);
                    out.flush();
                    System.out.println("ENQ reçu → ACK envoyé");
                } else if (data == STX) 
                {
                    inFrame = true;
                    frame.setLength(0);
                } else if (data == ETX && inFrame) 
                {
                    frame.append((char) data);
                    // Read checksum (2 chars) + CR + LF
                    char c1 = (char) in.read();
                    char c2 = (char) in.read();
                    char cr = (char) in.read();
                    char lf = (char) in.read();
                    String checksum = "" + c1 + c2;

                    String content = frame.toString();
                    if (verifyChecksum(content, checksum)) 
                    {
                        out.write(ACK);
                        out.flush();
                        System.out.println("Trame valide reçue, envoi MLLP...");
                        //parser le message sur l'écran
                        if (parseastm)
                        {
                            String fullMessage = content + checksum + cr + lf;
                            parseAstmMessage(fullMessage);
                        }
                        // envoyer vers une socket mllp
                        if (viamllp)
                        {
                            sendViaMLLP(content + checksum + cr + lf);
                        }
                        //ecrire le message sur disque
                        if (viafile)
                        {
                            String fullMessage = content + cr + lf;
                            writeAstmMessage(fullMessage, outputDirectory);
                        }
                    } else 
                    {
                        out.write(NAK);
                        out.flush();
                        System.out.println("Checksum invalide → NAK envoyé.");
                    }
                    inFrame = false;
                } else if (inFrame) {
                    frame.append((char) data);
                } else if (data == EOT) {
                    System.out.println("Fin de transmission (EOT)");
                    break;
                }
            }
        } catch (IOException e) {System.out.println(e.getMessage());}
    }

    /************************************
     * Calcul du checksum à la reception
     * @param frame
     * @param receivedChecksum
     * @return 
     ***********************************/
    private boolean verifyChecksum(String frame, String receivedChecksum) 
    {
        int sum = 0;
        for (char c : frame.toCharArray()) {
            sum += c;
        }
        int calculated = sum % 256;
        String hex = String.format("%02X", calculated);
        return hex.equalsIgnoreCase(receivedChecksum);
    }

    /*********************************************
     * Envoyer le message vers une socket TCP/IP
     * encapsulé MLLPv2
     * @param message 
     ********************************************/
    private void sendViaMLLP(String message) 
    {
        try (Socket mllpSocket = new Socket(MLLP_HOST, MLLP_PORT);
         OutputStream mllpOut = mllpSocket.getOutputStream();
         InputStream mllpIn = mllpSocket.getInputStream())
        {
        // Encapsulation MLLP
        ByteArrayOutputStream mllpMessage = new ByteArrayOutputStream();
        mllpMessage.write(VT);
        mllpMessage.write(message.getBytes(StandardCharsets.UTF_8));
        mllpMessage.write(FS);
        mllpMessage.write(CR);

        // Envoi du message
        mllpOut.write(mllpMessage.toByteArray());
        mllpOut.flush();
        System.out.println("Message envoyé via MLLP.");

        // Lecture de l'ACK
        ByteArrayOutputStream ackBuffer = new ByteArrayOutputStream();
        int b;
        while ((b = mllpIn.read()) != -1) {
            ackBuffer.write(b);
            if (b == FS) { // Fin du message ACK
                mllpIn.read(); // Lire le CR suivant
                break;
            }
        }

        String ackMessage = ackBuffer.toString(StandardCharsets.UTF_8);
        System.out.println("ACK reçu : " + ackMessage);

        // Écriture dans le fichier log
        LOGGER.log(Level.INFO, "ACK re\u00e7u : {0}", ackMessage);

        } catch (IOException e) {
        System.err.println("Erreur lors de l'envoi MLLP : " + e.getMessage());
        }
    }

    
    /*****************************
     * Simple parser format ASTM
     * @param message 
     *****************************/
    private void parseAstmMessage(String message) 
    {
        String[] lines = message.split("\r");
        for (String line : lines) 
        {
            if (line.startsWith("H|")) {
                System.out.println("Header (H): " + line);
            } else if (line.startsWith("P|")) {
                System.out.println("Patient (P): " + line);
            } else if (line.startsWith("O|")) {
                System.out.println("Order (O): " + line);
            } else if (line.startsWith("R|")) {
                System.out.println("Result (R): " + line);
            } else if (line.startsWith("L|")) {
                System.out.println("Terminator (L): " + line);
            } else {
                System.out.println("Autre segment : " + line);
            }
        }
    }

    /*******************************
     * Ecrire le message sur disque
     * @param fullMessage 
     *******************************/
    private void writeAstmMessage(String fullMessage, String outputDirectory) 
    {
        PrintWriter pw = null;
        try {
            int numsequence = (int)((Math.random() * 9998) + 1);
            String nomfichier = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_" + String.format("%04d", numsequence) + ".astm";
            File dir = new File(outputDirectory);
            if (!dir.exists()) dir.mkdirs(); //si n'existe pas le creer
            File file = new File(dir, nomfichier);
            pw = new PrintWriter(file);
            pw.write(fullMessage);
            pw.flush();
            System.out.println("Fichier écrit : " + file.getAbsolutePath());
        } catch (FileNotFoundException ex) {
            LOGGER.log(Level.SEVERE, "Erreur d'écriture du fichier ASTM", ex);
        } finally {
            if (pw != null) pw.close();
        }
    }

}
