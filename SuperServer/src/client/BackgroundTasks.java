package client;

import javafx.scene.control.ChoiceDialog;
import server.FileEntry;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * does all background tasks
 * checks for new files on disk
 * asks server for files
 * downloads and uploads files
 * updates files in gui
 */
class BackgroundTasks implements Runnable{
    private Thread t;
    private boolean loop;
    private ArrayList<FileEntry> filesLocal = new ArrayList<>();
    private ArrayList<FileEntry> filesServer = new ArrayList<>();
    private ArrayList<FileEntry> listForGui = new ArrayList<>();
    private String localFolder, username;
    private Controller c;
    private Socket socket;
    private PrintWriter out;
    private Scanner in;
    private String ip;
    private int port;
    private int waitTime = 5;
    private boolean loggedIn = false;

    /**
     *
     * @param localFolder folder in which user folders will be created
     * @param username login
     * @param ip server's ip address
     * @param port server's port
     * @param c reference to gui controller
     */
    BackgroundTasks(String localFolder, String username, String ip, int port, Controller c) {
        this.localFolder = localFolder;
        this.username = username;
        this.ip = ip;
        this.port = port;
        this.c = c;
        loop = true;
        if(t == null){
            this.t = new Thread(this);
            t.start();
        }
    }

    @Override
    public void run() {
        System.out.println("BG thread srtared");
        if(socket == null)createSocket();
        loggedIn = login();
        if(loggedIn){
            c.printText("Connected and logged in as: " + username);
        }else{
            c.printText("Trying to connect to server ... ");
        }
        new File(localFolder + File.separator + File.separator + username).mkdirs();
        int n = 2;
        while (loop) {
            n--;
            if(socket == null)createSocket();
            if(socket == null || socket.isClosed()){
                c.printText("Can't connect to server");
            }else{
                if(!loggedIn){
                    loggedIn = login();
                    if(loggedIn){
                        c.printText("Connected and logged in as: " + username);
                    }else{
                        c.printText("Trying to connect to server ... ");
                    }
                }
            }

            //-------------------------------------------read files from hdd
            filesLocal = getFilesHdd();
            //------------------------------------------get list of files from server
            if(socket != null) {
                filesServer = getFilesServer();
            }
            ArrayList<FileEntry> filesServerCopy = (ArrayList<FileEntry>)filesServer.clone();

            //update owners n others in local list

            for(int i_local = 0; i_local < filesLocal.size(); i_local++){
                String name = filesLocal.get(i_local).getFilename();
                String owner = filesLocal.get(i_local).getOwner();
                for(Iterator<FileEntry> i_server = filesServerCopy.iterator(); i_server.hasNext();){
                    FileEntry fs = i_server.next();
                    if(fs.getFilename().equals(name) && fs.getOwner().equals(owner)){
                        //found local file on filesServerCopy, now update owner and stuff



                        filesLocal.get(i_local).setOthers(fs.getOthers());
                        filesLocal.get(i_local).setHddNo(fs.getHddNo());
                        filesLocal.get(i_local).setStatus("local + server");
                        //delete entry from filesServerCopy
                        i_server.remove();
                        break;
                    }
                }
            }


            //create list for gui
            listForGui.clear();
            listForGui.addAll(filesLocal);
            //add files that are only on server
            listForGui.addAll(filesServerCopy);
            //send list to gui
            c.updateFiles(listForGui);

            //filesServer = filesServerCopy;

            //create list of files to download/upload
            ArrayList<FileEntry> downloadList = getDownloadList();
            ArrayList<FileEntry> uploadList = getUploadList();

            printList(filesLocal, "filesLocal");
            printList(filesServer, "filesServer");
            printList(listForGui, "listForGui");

            if(n <= 0){
                c.setTextLeft("Syncing with server ... downloading ");
                n = 5;
                if(downloadList.size() > 0 && socket != null){
                    printList(downloadList, "downloadList");
                    download(downloadList);
                }
                c.setTextLeft("Syncing with server ... uploading ");
                if(uploadList.size() > 0  && socket != null){
                    printList(uploadList, "uploadList");
                    upload(uploadList);
                }
            }
            System.out.println("-------------------------- sync with server in "+n*waitTime+"s --------------------");
            c.setTextLeft("Next sync with server in "+n*waitTime+"s");
            try{
                Thread.sleep(waitTime * 1000);
            }catch(InterruptedException e){}

        }
        logout();
        closeSocket();
        System.out.println("BG thread ended");
    }

    /**
     * tries to stop thread
     */
    public void stop(){
        loop = false;
        t.interrupt();
    }

    /**
     * gets list of all users from server
     * @return server's list of users
     */
    public ArrayList<String> getUsersFromServer(){
        ArrayList<String> list = null;
        try {
            out.println("getusers");
            ObjectInputStream objectInput = new ObjectInputStream(socket.getInputStream()); //Error Line!
            try {
                Object object = objectInput.readObject();
                list = (ArrayList<String>) object;
            } catch (ClassNotFoundException e) {
                //e.printStackTrace();
            }
        } catch (Exception e) {
            //e.printStackTrace();
        }
        return list;
    }
    /**
     * opens new dialog window
     * asks from whom take away rights to given file
     * sends "unshare" command to server
     * @param f file to unshare
     */
    public void unshare(FileEntry f){
        System.out.println("file to unshare : "+f);
        if(f.getOwner().equals(username)){
            //open new window and ask from whom unshare this file
            List<String> choices = f.getOthers();
            if(choices.size() > 0){
                ChoiceDialog<String> dialog = new ChoiceDialog<>(null, choices);
                dialog.setTitle("Unshare file");
                dialog.setHeaderText("Unshare file "+f.getFilename()+" from:");
                dialog.setContentText("Choose user:");
                Optional<String> result = dialog.showAndWait();
                result.ifPresent(letter -> {
                    System.out.println("Your choice: " + letter);
                    out.println("unshare "+f.getFilename() +" "+f.getOwner()+" "+letter);
                    //c.updateFilesForce(listForGui);
                });
            }else{
                c.dialog("SuperClient - info", "There's nobody from whom you can unshare this file.");
            }

        }else{
            c.dialog("SuperClient - info", "You can't share somebody else's file");
        }
    }

    /**
     * opens new dialog window
     * asks from to whom give rights to given file
     * sends "share" command to server
     * @param f file to share
     */
    public void share(FileEntry f){
        System.out.println("file to share : "+f);
        if(f.getOwner().equals(username)){
            //open new window and ask to whom share this file
            List<String> choices = getUsersFromServer();
            if(choices == null){
                //cant get users from server
                return;
            }
            choices.removeAll(f.getOthers());
            choices.remove(username);
            if(choices.size() > 0){
                ChoiceDialog<String> dialog = new ChoiceDialog<>(null, choices);
                dialog.setTitle("Share file");
                dialog.setHeaderText("Share file "+f.getFilename()+" to:");
                dialog.setContentText("Choose user:");
                Optional<String> result = dialog.showAndWait();
                result.ifPresent(letter -> {
                    System.out.println("Your choice: " + letter);
                    out.println("share "+f.getFilename() +" "+f.getOwner()+" "+letter);
                });
            }else{
                c.dialog("SuperClient - info", "There's nobody to share this file to.");
            }

        }else{
            c.dialog("SuperClient - info", "You can't share somebody else's file");
        }
    }

    /**
     * sends "delete" commands to server
     * if server could delete file then deletes file from drive
     * @param f file to delete
     */
    public void delete(FileEntry f){
        //send delete command to server
        out.println("delete "+f.getFilename()+" "+f.getOwner());
        //get msg from server, if it deleted file
        String msg = "";
        if(in.hasNextLine()) {
            msg = in.nextLine();
        }
        if(msg.equals("deleted")){
            //delete file from hdd
            if(new File(f.getPath()).delete()){
                //System.out.println("deleted successfully: "+f.getFilename());
                c.printText("File: \""+f.getFilename()+"\" has been deleted");
            }else {
                System.out.println("didn't delete: " + f.getFilename());
                c.printText("Error: File: \""+f.getFilename()+"\" has not been deleted (could't delete file from local folder)");
            }
        }else{
            //server couldn't delete file
            c.printText("Error: File: \""+f.getFilename()+"\" has not been deleted (server could't delete file)");
        }
    }

    /**
     * prints list
     * @param list list to print
     * @param name name of list
     */
    private void printList(ArrayList<FileEntry> list, String name){
        System.out.print(name + "{"+list.size()+"} : ");
        for(FileEntry f : list){
            System.out.print(f.getFilename() + ":" +f.getOwner() + ":"+f.getOthers()+"; ");
        }
        System.out.println();
    }

    /**
     * creates socket
     */
    private void createSocket(){
        try {
            socket = new Socket(ip, port);
        } catch (Exception e) {
            System.out.println("BG> Can't create socket.");
        }
        if(socket != null){
            try {
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new Scanner(socket.getInputStream());
            } catch (IOException e) {
                System.out.println("BG> Failed to get output stream from server");
            }
        }
    }

    /**
     * tries to close socket
     */
    private void closeSocket(){
        if(socket != null){
            out.println("exit");
            try{
                socket.close();
            }catch(Exception e){

            }
        }
    }

    /**
     * tries to login to server
     * @return true if login was successful
     */
    private boolean login(){
        try{
            out.println("login "+username);
            return true;
        }catch(Exception e){
            return false;
        }
    }

    /**
     * logs out from server
     */
    private void logout(){
        if(socket != null)out.println("logout "+username);
    }

    /**
     * creates list of files in local folder
     * @return list of file in local filder
     */
    private ArrayList<FileEntry> getFilesHdd(){
        ArrayList<FileEntry> filesHdd = new ArrayList<>();

        ArrayList<String> listHdd = listFilesForFolder(new File(localFolder));
        System.out.println("listHdd: " + listHdd);
        for(String fPath : listHdd){
            //if(fPath.contains(csvFileName))continue;//ignore csv file
            File file = new File(fPath);
            if (!file.isFile()) {
                continue;//file doesn't exist, should't happen though
            }else{
                //todo: if file contains unsupported characters, change them to _
                String fileNameRegex = "[\\w-_()'.]+\\.[A-Za-z0-9]+";
                String newFileName = file.getName();
                if(!file.getName().matches(fileNameRegex)){
                    //replace [^\w-_()'.] with _
                    newFileName = newFileName.replaceAll("[^\\w-_()'.]", "_");
                    //change file name
                    File file2 = new File(localFolder +File.separator+File.separator+ username +File.separator+File.separator+ newFileName);
                    boolean success = file.renameTo(file2);
                    if (!success) {
                        // File was not successfully renamed
                        c.printText("File : \""+file.getName()+"\" can't be loaded because filename duplicate occurred");
                        continue;
                    }
                }
                int size = (int)file.length();
                //get owner/folder name
                String pattern = Pattern.quote(System.getProperty("file.separator"));
                String[] split = fPath.split(pattern);
                //System.out.println("found file: "+split[split.length-1]+", owner: "+split[split.length-2]);
                filesHdd.add(new FileEntry(
                        newFileName,
                        0,
                        file.getPath().replace("\\", "\\\\"),
                        size,
                        split[split.length-2],
                        "local"));
            }
        }
        return filesHdd;
    }

    /**
     * used in getFilesHdd
     * lists all files/folders in folder
     * @param folder folder to scan for files/folders
     * @return list of all files and folder in folder
     */
    private ArrayList<String> listFilesForFolder(final File folder) {
        ArrayList<String> list = new ArrayList<String>();
        try {
            for (final File fileEntry : folder.listFiles()) {
                if (fileEntry.isDirectory()) {
                    list.addAll(listFilesForFolder(fileEntry));
                } else {
                    list.add(fileEntry.getPath());
                }
            }
        } catch (NullPointerException e) {

        }
        return list;
    }

    /**
     * gets from server list of all files user has rights to(owns them or they are shared to him)
     * @return list of files user has rights to
     */
    private ArrayList<FileEntry> getFilesServer(){
        ArrayList<FileEntry> list = new ArrayList<>();
        if(socket == null)
            return list;
        out.println("list " + username);
        try {
            ObjectInputStream objectInput = new ObjectInputStream(socket.getInputStream()); //Error Line!
            try {
                Object object = objectInput.readObject();
                list = (ArrayList<FileEntry>) object;
            } catch (ClassNotFoundException e) {
                //e.printStackTrace();
            }
        } catch (IOException e) {
            //e.printStackTrace();
        }
        return list;
    }

    /**
     * creates fils of files to download
     * @return fils tof files to download
     */
    private ArrayList<FileEntry> getDownloadList(){
        ArrayList<FileEntry> list = (ArrayList<FileEntry>)filesServer.clone();
        for(Iterator<FileEntry> i = list.iterator();i.hasNext();){
            FileEntry f = i.next();
            for(FileEntry fs : filesLocal){
                if(fs.getFilename().equals(f.getFilename()) && fs.getOwner().equals(f.getOwner())){
                    //same filename
                    i.remove();
                    break;
                }
            }
        }
        return list;
    }
    /**
     * creates fils of files to upload
     * @return fils tof files to upload
     */
    private ArrayList<FileEntry> getUploadList(){
        ArrayList<FileEntry> list = (ArrayList<FileEntry>)filesLocal.clone();
        //files local - files on server
        //also skip files that you're not the owner of
        for(Iterator<FileEntry> i = list.iterator();i.hasNext();){
            FileEntry f = i.next();
            if(!f.getOwner().equals(username)){
                //am not owner, no need to upload somebody's files
                i.remove();
                continue;
            }
            for(FileEntry fs : filesServer){
                if(fs.getFilename().equals(f.getFilename()) && fs.getOwner().equals(f.getOwner())){
                    //same filename
                    i.remove();
                    break;
                }
            }
        }
        return list;
    }

    /**
     * waits for all tasks in thread pool to finish
     * @param threadPool thread pool with tasks
     */
    private void awaitTerminationAfterShutdown(ExecutorService threadPool) {
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(60, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException ex) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * downloads files
     * @param list list of files to download
     * @return number of files downloaded
     */
    private int download(ArrayList<FileEntry> list){
        if(socket == null)return -1;
        int n = list.size();
        System.out.println("download> Files to download:"+list);
        //create thread pool
        ExecutorService poolDownload = Executors.newFixedThreadPool(n);
        System.out.println("download> Downloading "+n+" files ...");
        c.addTextLeft(n+"files ");
        Iterator<FileEntry> i = list.iterator();
        while (i.hasNext()) {
            FileEntry f = i.next();
            poolDownload.execute(new FileDownloadClass(c, ip, port, username, f, localFolder));
            try{
                t.sleep(100);
            }catch(InterruptedException e){}
        }
        System.out.println("download> Waiting for "+n+" files to download ... ");
        awaitTerminationAfterShutdown(poolDownload);
        System.out.println("download> Downloaded "+n+" files!");
        return n;
    }
    /**
     * uploads files
     * @param list list of files to upload
     * @return number of files uploaded
     */
    private int upload(ArrayList<FileEntry> list){
        if(socket == null)return -1;
        int n = list.size();
        System.out.println("upload> files to upload:"+list);
        //create thread pool
        ExecutorService poolUpload = Executors.newFixedThreadPool(n);
        System.out.println("upload> uploading "+n+" file(s) ...");
        c.addTextLeft(n+"files ");
        Iterator<FileEntry> i = list.iterator();
        while (i.hasNext()) {
            FileEntry f = i.next();
            poolUpload.execute(new FileUploadClass(this, c, ip, port, f, localFolder, username));
            try{
                t.sleep(100);
            }catch(InterruptedException e){}
        }
        System.out.println("upload> Waiting for "+n+" files to upload ... ");
        awaitTerminationAfterShutdown(poolUpload);
        System.out.println("upload> uploaded "+n+" file(s)!");
        return n;
    }

}
