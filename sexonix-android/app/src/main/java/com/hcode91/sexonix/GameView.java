package com.hcode91.sexonix;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Native, original-code Xonix-compatible gameplay prototype. JAR assets are
 * loaded through the local import flow; copyrighted images are not bundled.
 * Gameplay values are approximated until cross-checked against J2ME reference.
 */
public final class GameView extends View {
    private static final int SIZE = 128, SCREEN_H = 160, TARGET = 75;
    private static final int EMPTY = 0, OWNED = 1, TRAIL = 2;
    private static final int MENU = 0, PLAY = 1, LEVEL_DONE = 2, GAME_OVER = 3;
    // The original prototype's 64ms movement interval made the player
    // significantly slower than enemies, whose physics updates every 34ms.
    // Movement cadence is now independent from render cadence.
    private static final int[] PLAYER_STEP_MS = {34, 26, 20};
    private static final String[] SPEED_NAMES = {"Gentle", "Balanced", "Fast"};
    private final Paint paint = new Paint();
    private final Random random = new Random();
    private final int[] cells = new int[SIZE * SIZE];
    private final List<Ball> balls = new ArrayList<>();
    private final Bitmap[] pictures = new Bitmap[7];
    private final Bitmap[] faded = new Bitmap[7];
    private final Rect src = new Rect(0,0,SIZE,SIZE);
    private final Rect dst = new Rect();
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (getWindowToken() == null) return;
            tick();
            invalidate();
            postDelayed(this, 16);
        }
    };
    private int state = MENU, level, lives = 4, score, target = TARGET, percent;
    private int px = SIZE / 2, py = 1, dx = 0, dy = 0, trailLength;
    private boolean drawing, hasAssets, paused, wasOnOwn;
    private long lastMove, lastBall, lastHit;
    private float scale, ox, oy;
    private String status = "Import your J2ME JAR to load the original stage images.";
    private int touchDir;
    private boolean drawHeld;
    private int speedIndex = 1; // Balanced is the default for existing installs.

    public int getSpeedIndex() { return speedIndex; }

    public String speedLabel() { return SPEED_NAMES[speedIndex]; }

    public void setSpeedIndex(int value) {
        if (value < 0 || value >= PLAYER_STEP_MS.length) value = 1;
        speedIndex = value;
        // Apply immediately, without replaying accumulated old timing.
        lastMove = android.os.SystemClock.uptimeMillis();
        invalidate();
    }

    private static final class Ball {
        float x,y,vx,vy;
        Ball(float x,float y,float vx,float vy) {
            this.x=x;this.y=y;this.vx=vx;this.vy=vy;
        }
    }

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        paint.setAntiAlias(false);
        startLevel();
    }

    public void setStage(int i, Bitmap light, Bitmap dark) {
        if(i < 0 || i >= pictures.length) return;
        if (light != null && light.getWidth()==SIZE && light.getHeight()==SIZE) {
            pictures[i] = light;
            faded[i] = dark != null ? dark : light;
        }
        hasAssets = pictures[0] != null;
        status = hasAssets ? "Original stage images imported locally."
                : "Import a J2ME JAR containing all seven .dat stages.";
        invalidate();
    }
    public void reportError(String text) { status = text; invalidate(); }
    public int availableLevels() {
        int found=0;
        for(Bitmap img:pictures) if(img!=null)found++;
        return found;
    }

    private void startLevel() {
        Arrays.fill(cells, EMPTY);
        for (int y=0;y<SIZE;y++) for(int x=0;x<SIZE;x++)
            if(x<3 || x>=SIZE-3 || y<3 || y>=SIZE-3) cells[y*SIZE+x] = OWNED;
        px=SIZE/2;py=1;dx=0;dy=0;
        drawing=false;trailLength=0;percent=0;
        balls.clear();
        int count=2+Math.min(3,level);
        for(int i=0;i<count;i++) {
            float vx=(random.nextBoolean()?1:-1)*(0.8f+random.nextFloat()*0.4f);
            float vy=(random.nextBoolean()?1:-1)*(0.75f+random.nextFloat()*0.45f);
            balls.add(new Ball(35+i*11,40+i*9,vx,vy));
        }
        lastBall=lastMove=android.os.SystemClock.uptimeMillis();
    }

    private void newGame() {
        level=0;lives=4;score=0;paused=false;
        startLevel();state=PLAY;
    }

    private void tick() {
        if (state != PLAY || paused) return;
        long now = android.os.SystemClock.uptimeMillis();

        // Keep enemy movement unchanged at 34ms per physics step.
        // Catch up a few steps if the UI frame is late; avoid huge bursts
        // after the app has been backgrounded.
        long ballElapsed = now - lastBall;
        if (ballElapsed >= 34) {
            int steps = (int)Math.min(5, ballElapsed / 34);
            lastBall += steps * 34L;
            if (now - lastBall > 170) lastBall = now - 34;
            for (int i = 0; i < steps && state == PLAY; i++) updateBalls();
        }
        if (state != PLAY) return;

        int mx = dx, my = dy;
        if (touchDir == 1) { mx = 0; my = -1; }
        else if (touchDir == 2) { mx = 0; my = 1; }
        else if (touchDir == 3) { mx = -1; my = 0; }
        else if (touchDir == 4) { mx = 1; my = 0; }
        if (mx == 0 && my == 0) {
            lastMove = now; // No accumulated moves after standing still.
            return;
        }

        int period = PLAYER_STEP_MS[speedIndex];
        long elapsed = now - lastMove;
        if (elapsed >= period) {
            int steps = (int)Math.min(8, elapsed / period);
            lastMove += steps * (long)period;
            if (now - lastMove > 8L * period) lastMove = now - period;
            for (int i = 0; i < steps && state == PLAY; i++) {
                if (!movePlayer(mx, my)) break;
            }
        }
    }

    /** Single-pixel step keeps trail and collision checks intact at all speeds. */
    private boolean movePlayer(int mx, int my) {
        boolean fromOwn = cells[py * SIZE + px] == OWNED;
        boolean wantsDraw = drawHeld || drawing;
        int nx = Math.max(0, Math.min(SIZE - 1, px + mx));
        int ny = Math.max(0, Math.min(SIZE - 1, py + my));
        if (nx == px && ny == py) return false;
        int value = cells[ny * SIZE + nx];
        if (value == TRAIL || (value == EMPTY && !wantsDraw)) return false;
        if (value == EMPTY && wantsDraw && fromOwn) {
            drawing = true;
            trailLength = 0;
        }
        if (value == EMPTY && drawing) {
            cells[ny * SIZE + nx] = TRAIL;
            trailLength++;
        }
        px = nx;
        py = ny;
        if (drawing && value == OWNED && trailLength > 0) {
            drawing = false;
            captureTerritory();
            if (percent >= target) {
                state = LEVEL_DONE;
                score += 1000 * (level + 1);
            }
        }
        return true;
    }

    private void updateBalls() {
        for(Ball b: balls) {
            float nx=b.x+b.vx,ny=b.y+b.vy;
            if(nx<4||nx>SIZE-5||cells[((int)b.y)*SIZE+Math.max(0,Math.min(SIZE-1,(int)nx))]==OWNED) {
                b.vx=-b.vx;nx=b.x+b.vx;
            }
            if(ny<4||ny>SIZE-5||cells[Math.max(0,Math.min(SIZE-1,(int)ny))*SIZE+(int)b.x]==OWNED) {
                b.vy=-b.vy;ny=b.y+b.vy;
            }
            b.x=Math.max(4,Math.min(SIZE-5,nx));
            b.y=Math.max(4,Math.min(SIZE-5,ny));
            int ix=(int)b.x,iy=(int)b.y;
            for(int yy=Math.max(3,iy-1);yy<=Math.min(SIZE-4,iy+1);yy++)
              for(int xx=Math.max(3,ix-1);xx<=Math.min(SIZE-4,ix+1);xx++)
                if(cells[yy*SIZE+xx]==TRAIL) {
                    loseLife();return;
                }
            if(Math.abs(b.x-px)<2 && Math.abs(b.y-py)<2) {loseLife();return;}
        }
    }

    private void loseLife() {
        long now=android.os.SystemClock.uptimeMillis();
        if(now-lastHit<700)return;
        lastHit=now;lives--;
        for(int i=0;i<cells.length;i++) if(cells[i]==TRAIL)cells[i]=EMPTY;
        px=SIZE/2;py=1;drawing=false;trailLength=0;touchDir=0;
        if(lives<=0) state=GAME_OVER;
    }

    private void captureTerritory() {
        // Flood fill from all enemies; territory not reachable by an enemy
        // becomes captured. This reproduces the central mechanic of Qix/Xonix.
        boolean[] reachable = new boolean[cells.length];
        ArrayDeque<Integer> work=new ArrayDeque<>();
        for(Ball b:balls) {
            int idx=(int)b.y*SIZE+(int)b.x;
            if(idx>=0&&idx<cells.length&&cells[idx]==EMPTY&&!reachable[idx]) {
                reachable[idx]=true;work.add(idx);
            }
        }
        while(!work.isEmpty()) {
            int p=work.removeFirst(),x=p%SIZE,y=p/SIZE;
            if(x>0) push(p-1,reachable,work);
            if(x<SIZE-1) push(p+1,reachable,work);
            if(y>0) push(p-SIZE,reachable,work);
            if(y<SIZE-1) push(p+SIZE,reachable,work);
        }
        int captured=0;
        for(int i=0;i<cells.length;i++) {
            if(cells[i]==TRAIL || (cells[i]==EMPTY && !reachable[i])) {
                cells[i]=OWNED;captured++;
            }
        }
        score+=captured*10;
        int owned=0;
        for(int i=0;i<cells.length;i++)if(cells[i]==OWNED)owned++;
        int border=SIZE*SIZE-(SIZE-6)*(SIZE-6);
        percent=Math.min(100,Math.max(0,(owned-border)*100/((SIZE-6)*(SIZE-6))));
    }

    private void push(int idx,boolean[] reachable,ArrayDeque<Integer> work) {
        if(cells[idx]==EMPTY&&!reachable[idx]) {
            reachable[idx]=true;work.add(idx);
        }
    }

    private void direction(int value) {
        // First key/arrow press responds on the next UI frame, rather than
        // forcing the player to wait for the full movement interval.
        boolean wasMoving = touchDir != 0 || dx != 0 || dy != 0;
        if (!wasMoving && value != 0)
            lastMove = android.os.SystemClock.uptimeMillis()
                    - PLAYER_STEP_MS[speedIndex];
        touchDir=value;
        switch(value) {
            case 1:dx=0;dy=-1;break;
            case 2:dx=0;dy=1;break;
            case 3:dx=-1;dy=0;break;
            case 4:dx=1;dy=0;break;
            default:dx=0;dy=0;
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();removeCallbacks(frame);post(frame);
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame);super.onDetachedFromWindow();
    }

    private void text(Canvas c,String s,float x,float y,int size,int color,boolean center) {
        paint.setColor(color);paint.setTextSize(size);paint.setTypeface(
                android.graphics.Typeface.create("monospace",android.graphics.Typeface.BOLD));
        paint.setStyle(Paint.Style.FILL);
        if(center)x-=paint.measureText(s)/2;
        c.drawText(s,x,y,paint);
    }

    private void box(Canvas c,float l,float t,float r,float b,int color) {
        paint.setStyle(Paint.Style.FILL);paint.setColor(color);
        c.drawRect(l,t,r,b,paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(11,12,20));
        // Logical 128x160 viewport, independently scaled to the device.
        scale=Math.min(getWidth()/128f,getHeight()/220f);
        ox=(getWidth()-128*scale)/2f;
        oy=(getHeight()-220*scale)/2f;
        canvas.save();canvas.translate(ox,oy);canvas.scale(scale,scale);
        box(canvas,0,0,128,220,Color.rgb(20,15,29));
        box(canvas,0,0,128,SCREEN_H,Color.rgb(109,18,32));
        if(state==MENU) {
            text(canvas,"SeXoniX",64,32,18,Color.WHITE,true);
            text(canvas,"NATIVE ANDROID",64,48,9,0xffead46c,true);
            text(canvas,hasAssets?"7-stage JAR loaded":"Import your JAR",64,67,9,0xffd6f3d1,true);
            text(canvas,"TAP TO START",64,99,12,Color.WHITE,true);
            text(canvas,hasAssets?"ORIGINAL LEVEL ART":"PROTOTYPE GRAPHICS",64,117,8,0xffa3b0cb,true);
            text(canvas,"Capture 75% to win",64,137,9,Color.WHITE,true);
        } else {
            Bitmap picture=pictures[level%7];
            Bitmap dark=faded[level%7];
            if(picture!=null) {
                dst.set(0,0,SIZE,SIZE);
                paint.setColor(Color.WHITE);
                canvas.drawBitmap(dark,src,dst,paint);
                // Draw original bright palette only in captured territory using
                // per-run rectangular clips, without modifying copyrighted image.
                for(int y=0;y<SIZE;y++) {
                    int x=0;
                    while(x<SIZE) {
                        while(x<SIZE && cells[y*SIZE+x]!=OWNED)x++;
                        int start=x;
                        while(x<SIZE && cells[y*SIZE+x]==OWNED)x++;
                        if(x>start) {
                            canvas.save();
                            canvas.clipRect(start,y,x,y+1);
                            canvas.drawBitmap(picture,src,dst,paint);
                            canvas.restore();
                        }
                    }
                }
            } else box(canvas,0,0,SIZE,SIZE,0xff3e1b39);
            // Start by showing mostly concealed interior. White/green boundary
            // and trails make gameplay readable even with dense original art.
            for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++) {
                int kind=cells[y*SIZE+x];
                if(picture==null&&kind==OWNED)box(canvas,x,y,x+1,y+1,0xffb13b48);
                if(kind==TRAIL)box(canvas,x,y,x+1,y+1,0xfffafb8d);
            }
            for(Ball b:balls)box(canvas,b.x-2,b.y-2,b.x+2,b.y+2,0xffffffff);
            box(canvas,px-2,py-2,px+2,py+2,0xff00ff86);
            box(canvas,0,128,128,160,0xffa11d2d);
            text(canvas,"LIFE "+lives+"  SCORE "+score,3,140,9,0xffffffff,false);
            text(canvas,"LEVEL "+(level+1),3,152,9,0xfff0dd63,false);
            text(canvas,percent+"% / "+target+"%",125,152,9,0xfff0dd63,true);
            if(state==LEVEL_DONE||state==GAME_OVER) {
                box(canvas,7,44,121,103,0xe8111723);
                text(canvas,state==LEVEL_DONE?"LEVEL COMPLETE":"GAME OVER",64,64,12,Color.WHITE,true);
                text(canvas,"TAP / FIRE TO CONTINUE",64,84,8,0xffe2e76e,true);
            }
            if(paused)text(canvas,"PAUSED",64,98,15,Color.WHITE,true);
        }
        // D-pad and fire region use fixed logical hit targets.
        box(canvas,0,160,128,220,0xff1c2530);
        drawButton(canvas,8,178,37,207,"<");
        drawButton(canvas,44,163,77,189,"^");
        drawButton(canvas,44,191,77,216,"v");
        drawButton(canvas,85,178,115,207,">");
        text(canvas,"DRAW: HOLD SCREEN",64,176,6,0xffb3c0d8,true);
        canvas.restore();
    }
    private void drawButton(Canvas c,float l,float t,float r,float b,String label) {
        box(c,l,t,r,b,0xff42516b);
        text(c,label,(l+r)/2,(t+b)/2+5,16,Color.WHITE,true);
    }

    private int touchDirection(float x,float y) {
        if(y<160 || y>220) return 0;
        if(x<40)return 3;
        if(x>83)return 4;
        if(y<190)return 1;
        return 2;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        float x=(event.getX()-ox)/scale,y=(event.getY()-oy)/scale;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE) {
            if(state==MENU){newGame();invalidate();return true;}
            if(state==LEVEL_DONE) {
                level=(level+1)%7;startLevel();state=PLAY;return true;
            }
            if(state==GAME_OVER){newGame();return true;}
            int dir=touchDirection(x,y);
            if(dir!=0) {
                direction(dir);drawHeld=true;
            } else if(y<160) {
                drawHeld=true;
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL) {
            touchDir=0;dx=0;dy=0;drawHeld=false;
            return true;
        }
        return true;
    }
    @Override public boolean onKeyDown(int key,KeyEvent event) {
        if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER
                ||key==KeyEvent.KEYCODE_BUTTON_A) {
            if(state==MENU||state==GAME_OVER)newGame();
            else if(state==LEVEL_DONE){level=(level+1)%7;startLevel();state=PLAY;}
            else drawHeld=true;
            return true;
        }
        int dir=0;
        if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_W)dir=1;
        else if(key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_S)dir=2;
        else if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_A)dir=3;
        else if(key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_D)dir=4;
        if(dir!=0) {direction(dir);drawHeld=true;return true;}
        if(key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_ESCAPE) {
            paused=!paused;invalidate();return true;
        }
        return super.onKeyDown(key,event);
    }
    @Override public boolean onKeyUp(int key, KeyEvent event) {
        if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_BUTTON_A) {
            drawHeld=false;return true;
        }
        if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT
                ||key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN) {
            direction(0);drawHeld=false;return true;
        }
        return super.onKeyUp(key,event);
    }
}
