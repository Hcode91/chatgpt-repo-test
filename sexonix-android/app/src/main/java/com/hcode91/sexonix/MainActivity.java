package com.hcode91.sexonix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * No J2ME VM, original executable, or game assets in the APK.
 * The user may supply their own original SeXoniX JAR for locally decoded
 * level art. Never sends files or metadata to a network service.
 */
public final class MainActivity extends Activity {
    private static final int PICK_JAR = 40;
    private static final int MAX_ZIP_ENTRIES = 500;
    private static final int MAX_TOTAL = 8 * 1024 * 1024;
    private final byte[][] savedStages = new byte[7][];
    private GameView game;
    private TextView info;
    private Button speedButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff0b0d14);
        getWindow().setNavigationBarColor(0xff0b0d14);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0b0d14);

        LinearLayout top=new LinearLayout(this);
        top.setPadding(12,8,12,8);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=new TextView(this);
        title.setText("SeXoniX Android  •  Native Preview");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        top.addView(title,new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT,1));
        Button importButton=new Button(this);
        importButton.setText("Import JAR");
        importButton.setTextSize(11);
        top.addView(importButton);
        importButton.setOnClickListener(v->selectJar());
        root.addView(top);

        info=new TextView(this);
        info.setPadding(12,0,12,5);
        info.setTextColor(0xffb8e5b3);
        info.setTextSize(11);
        info.setText("Import your original J2ME JAR to display its stage artwork.");
        root.addView(info);

        game=new GameView(this);
        int savedSpeed = getPreferences(MODE_PRIVATE).getInt("playerSpeed", 1);
        game.setSpeedIndex(savedSpeed);
        speedButton = new Button(this);
        speedButton.setTextSize(11);
        speedButton.setText("Player speed: " + game.speedLabel());
        top.addView(speedButton);
        speedButton.setOnClickListener(v -> showSpeedDialog());
        root.addView(game,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        setContentView(root);
        restoreStages();
    }

    private void showSpeedDialog() {
        final String[] options = {
                "Gentle (34 ms / step)",
                "Balanced (26 ms / step)",
                "Fast (20 ms / step)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Player movement speed")
                .setSingleChoiceItems(options, game.getSpeedIndex(), (dialog, selected) -> {
                    game.setSpeedIndex(selected);
                    getPreferences(MODE_PRIVATE).edit()
                            .putInt("playerSpeed", selected).apply();
                    speedButton.setText("Player speed: " + game.speedLabel());
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void selectJar() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent,PICK_JAR);
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_JAR||result!=RESULT_OK||data==null
                ||data.getData()==null)return;
        Uri uri=data.getData();
        info.setText("Reading original JAR locally...");
        new Thread(()-> {
            try {
                byte[][] stages=readStages(uri);
                for(int i=0;i<7;i++) {
                    // Validate all stages BEFORE writing any permanent files.
                    LegacyLevelDecoder.decode(stages[i]);
                }
                File dir=new File(getFilesDir(),"stages");
                if(!dir.exists()&&!dir.mkdirs())throw new IOException("No private storage");
                for(int i=0;i<7;i++) {
                    File dest=new File(dir,(i+1)+".dat");
                    try(FileOutputStream out=new FileOutputStream(dest)) {
                        out.write(stages[i]);
                    }
                }
                runOnUiThread(()->{
                    try {
                        for(int i=0;i<7;i++)loadStage(i,stages[i]);
                        info.setText("7 original stages imported! Gameplay is a native prototype.");
                    } catch(Exception e) {
                        info.setText("Stage decode error: "+e.getMessage());
                    }
                });
            } catch(Exception e) {
                String message=e.getMessage();
                runOnUiThread(()->info.setText("Import failed: "+message));
            }
        },"jar-import").start();
    }

    private byte[][] readStages(Uri uri) throws IOException {
        byte[][] stages=new byte[7][];
        int entries=0,total=0;
        try(InputStream input=getContentResolver().openInputStream(uri)) {
            if(input==null)throw new IOException("Could not read selected JAR");
            try(ZipInputStream jar=new ZipInputStream(input)) {
                ZipEntry entry;
                while((entry=jar.getNextEntry())!=null) {
                    if(++entries>MAX_ZIP_ENTRIES)
                        throw new IOException("Too many JAR entries");
                    if(entry.isDirectory())continue;
                    String name=entry.getName();
                    // The original stages are exactly 1.dat ... 7.dat
                    boolean isStage=name.length()==5 && name.charAt(0)>='1'
                            && name.charAt(0)<='7' && name.endsWith(".dat");
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    byte[] buffer=new byte[8192];
                    int n;
                    while((n=jar.read(buffer))!=-1) {
                        total+=n;
                        if(total>MAX_TOTAL)throw new IOException("JAR exceeds 8MiB expanded limit");
                        if(isStage)bytes.write(buffer,0,n);
                    }
                    if(isStage)stages[name.charAt(0)-'1']=bytes.toByteArray();
                    jar.closeEntry();
                }
            }
        }
        for(int i=0;i<7;i++) if(stages[i]==null)
            throw new IOException("Missing "+(i+1)+".dat in JAR");
        return stages;
    }

    private void restoreStages() {
        File dir=new File(getFilesDir(),"stages");
        int loaded=0;
        for(int i=0;i<7;i++) {
            File file=new File(dir,(i+1)+".dat");
            if(!file.isFile())continue;
            try(FileInputStream input=new FileInputStream(file)) {
                if(file.length()>100000)continue;
                byte[] data=new byte[(int)file.length()];
                int off=0;
                while(off<data.length) {
                    int n=input.read(data,off,data.length-off);
                    if(n<0)throw new IOException("Truncated file");
                    off+=n;
                }
                loadStage(i,data);
                loaded++;
            }catch(Exception ignored) { }
        }
        if(loaded==7)info.setText("Original JAR level artwork loaded from private storage.");
    }

    private void loadStage(int index,byte[] raw) throws IOException {
        LegacyLevelDecoder.Images imgs=LegacyLevelDecoder.decode(raw);
        Bitmap bright=BitmapFactory.decodeByteArray(imgs.brightPng,0,imgs.brightPng.length);
        Bitmap dark=BitmapFactory.decodeByteArray(imgs.darkPng,0,imgs.darkPng.length);
        if(bright==null||dark==null)throw new IOException("Android could not decode stage");
        game.setStage(index,bright,dark);
    }
}
