package com.zafar.nfsu2remix;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;
import org.json.JSONObject;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.HashMap;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * On-screen controls drawn as icons, in the style of the MW port: round buttons, pedals and a steering
 * stick, no captions. One surface tracks independent fingers so steering and pedals can be held together.
 */
final class RacingControlsView extends View {
    interface Keys { void set(int scan, boolean down); }
    /** Touches outside every control drive the game's mouse: x/y as fractions of the view, phase 0 down, 1 move, 2 up. */
    interface Pointer { void touch(float x, float y, int phase); }
    // Special controls that do not map to a DirectInput scan code.
    private static final int MODE = -1, TEXT = -2, STICK = -3, EDIT = -5;
    private static final int ROUND = 0, PEDAL = 1, WHEEL = 2;
    private final Keys keys;
    private Pointer pointer;
    private int pointerId=-1;
    private float pointerX, pointerY;
    private final Runnable textInput;
    private final Runnable configureTilt;
    private final Runnable configureFrameLimit;
    private String style;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ArrayList<Control> controls = new ArrayList<>();
    private final HashMap<Integer, Control> fingers = new HashMap<>();
    private boolean racing;
    private boolean lastGameMode;
    private boolean editing;
    private boolean hiddenByGamepad;
    /** Makro: menekan tombol NOS (N2O, scan 0x38) otomatis menahan pedal gas (scan 0xc8) juga. Gas saja tidak menyalakan NOS. */
    private boolean macroNos;
    private boolean gamepadConnected;
    private Control selected;
    private int dragPointer = -1;
    private float dragX, dragY;
    private final SharedPreferences layouts;
    private float scale;
    private float globalScale = 1f;
    private int globalOpacity = 100;
    private static final int ACCENT = Color.rgb(255, 154, 50);
    private static final int NITRO = Color.rgb(61, 184, 255);
    private static final int RING_A = 0xFF8FEBFF, RING_B = 0xFF4A86FF;
    private static final int INK_DARK = 0xFF1A1208;
    private static final float[] COS = new float[24], SIN = new float[24];
    static {
        for (int i = 0; i < 24; ++i) { COS[i] = (float) Math.cos(i * Math.PI / 12); SIN[i] = (float) Math.sin(i * Math.PI / 12); }
    }
    private final DashPathEffect dash = new DashPathEffect(new float[]{9, 7}, 0);
    private final RectF box = new RectF();
    private int glyphHot = ACCENT;
    private static final class Control {
        final RectF area;
        final String icon;
        final int scan, shape, color;
        final RectF original;
        String id;
        int held;
        float steering;
        Shader body, bodyOn, ring;   // cached gradients
        float key;
        Control(float x, float y, float w, float h, int shape, String icon, int scan, int color) {
            area = new RectF(x, y, x+w, y+h); original=new RectF(area); this.shape=shape; this.icon=icon; this.scan=scan; this.color=color;
        }
    }
    RacingControlsView(Context context, Keys keys, Runnable textInput, Runnable configureTilt, Runnable configureFrameLimit) {
        super(context); this.keys=keys; this.textInput=textInput;
        layouts=context.getSharedPreferences("touch_layouts_v1",Context.MODE_PRIVATE);
        style=layouts.getString("style","classic");this.configureTilt=configureTilt;this.configureFrameLimit=configureFrameLimit;
        setContentDescription("Game controls");
        macroNos=layouts.getBoolean("macro_nos",true);
        globalScale = layouts.getFloat("global_scale", 1f);
        globalOpacity = layouts.getInt("global_opacity", 100);
        setHapticFeedbackEnabled(true);
        line.setStyle(Paint.Style.STROKE);line.setStrokeCap(Paint.Cap.ROUND);line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);text.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));
    }
    private void add(float x, float y, float size, String icon, int scan) {add(x,y,size,size,ROUND,icon,scan,0);}
    private void add(float x, float y, float w, float h, int shape, String icon, int scan, int color) {
        Control c=new Control(x,y,w,h,shape,icon,scan,color);
        // v2: icon layout; positions saved for the old captioned buttons do not apply.
        c.id="v2."+(racing?"race."+(style.equals("classic")?"":style+"."):"menu.")+scan;
        float factor=layouts.getFloat(c.id+".size",1)*globalScale;
        float cx=layouts.getFloat(c.id+".x",c.area.centerX()/(getWidth()/scale))*(getWidth()/scale);
        float cy=layouts.getFloat(c.id+".y",c.area.centerY()/(getHeight()/scale))*(getHeight()/scale);
        c.area.set(cx-w*factor/2,cy-h*factor/2,cx+w*factor/2,cy+h*factor/2);
        clamp(c);controls.add(c);
    }
    private void clamp(Control c) {
        float w=getWidth()/scale,h=getHeight()/scale;
        float dx=c.area.left<4?4-c.area.left:c.area.right>w-4?w-4-c.area.right:0;
        float dy=c.area.top<4?4-c.area.top:c.area.bottom>h-4?h-4-c.area.bottom:0;
        c.area.offset(dx,dy);
    }
    /** True jika mod ExtraOptions terpasang (nfsu2/scripts/NFSU2ExtraOptions.*). */
    private static boolean hasExtraOptions() {
        java.io.File dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "nfsu2/scripts");
        String[] names = dir.list();
        if (names == null) return false;
        for (String name : names)
            if (name.toLowerCase(java.util.Locale.ROOT).startsWith("nfsu2extraoptions")) return true;
        return false;
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { layoutControls(); }
    void setGameMode(boolean driving) {
        if(editing||lastGameMode==driving)return;
        lastGameMode=driving;racing=driving;layoutControls();
    }
    boolean isDriving() {return racing&&!editing&&!hiddenByGamepad;}
    /** A physical gamepad is connected: get out of the way until the screen is touched. */
    /** The pad was used while the controls were showing after a touch: get out of the way again. */
    void gamepadUsed() {
        if(!gamepadConnected||hiddenByGamepad||editing)return;
        releaseAll();hiddenByGamepad=true;invalidate();
    }
    void setGamepadConnected(boolean connected) {
        if(gamepadConnected==connected)return;
        gamepadConnected=connected;
        hiddenByGamepad=connected&&!editing;
        releaseAll();
    }
    private void layoutControls() {
        releaseAll(); controls.clear();selected=null;dragPointer=-1;
        if(getWidth()==0)return;
        scale=Math.min(getResources().getDisplayMetrics().density, getWidth()/820f);
        float w=getWidth()/scale, h=getHeight()/scale, bottom=h-20;
        add(w-62,16,46,"settings",EDIT);
        add(w-118,16,46,racing?"menu":"wheel",MODE);
        if(racing) {
            add(16,16,46,"pause",1);
            // Free-roam actions of the PC keyboard defaults (Opciones > Controles).
            add(72,16,46,"map",0x32);     // Mapa del mundo: M
            add(128,16,46,"sms",0x0f);    // Leer SMS: Tab
            add(184,16,46,"reset",0x13);  // Reiniciar coche: R
            if(style.equals("classic")) {
                add(20,bottom-92,92,"left",0xcb);
                add(124,bottom-92,92,"right",0xcd);
                add(228,bottom-66,62,"handbrake",0x39);
                add(30,bottom-160,52,"shiftUp",0x2a);
                add(92,bottom-160,52,"shiftDown",0x1d);
                add(154,bottom-160,52,"camera",0x2e);
                add(w-110,bottom-150,90,150,PEDAL,"gas",0xc8,0xFF1E7A4A);
                add(w-206,bottom-108,82,108,PEDAL,"brake",0xd0,0xFF8A2A2A);
                add(w-290,bottom-72,70,"nitro",0x38);
            } else {
                boolean xbox=style.equals("xbox");
                add(24,bottom-160,160,160,WHEEL,"stick",STICK,0);
                add(w-106,bottom-150,86,150,PEDAL,"gas",0xc8,0xFF1E7A4A);
                add(w-198,bottom-108,78,108,PEDAL,"brake",0xd0,0xFF8A2A2A);
                add(w-252,bottom-178,58,xbox?"A":"×",0x39);
                add(w-186,bottom-238,58,xbox?"B":"○",0x38);
                add(w-318,bottom-238,58,xbox?"X":"□",0x2e);
                add(w-252,bottom-298,58,xbox?"Y":"△",0x30);
                add(30,bottom-228,52,"shiftUp",0x2a);
                add(92,bottom-228,52,"shiftDown",0x1d);
            }
        } else {
            float pad=60,cx=24+pad*1.5f,cy=bottom-pad*1.5f;
            add(cx-pad/2,cy-pad*1.5f,pad,"up",0xc8);
            add(cx-pad/2,cy+pad*.5f,pad,"down",0xd0);
            add(cx-pad*1.5f,cy-pad/2,pad,"left",0xcb);
            add(cx+pad*.5f,cy-pad/2,pad,"right",0xcd);
            add(w-96,bottom-110,76,"accept",0x1c);
            add(w-176,bottom-62,56,"back",1);
            add(w/2-23,bottom-46,46,"keyboard",TEXT);
            if(hasExtraOptions()) add(w/2+40,bottom-46,46,"F5",0x3f);
        }
        invalidate();
    }

    // ---- Drawing -----------------------------------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(hiddenByGamepad&&!editing)return;
        int layer=-1;
        if(!editing&&globalOpacity<100)
            layer=canvas.saveLayerAlpha(0,0,getWidth(),getHeight(),globalOpacity*255/100);
        canvas.save(); canvas.scale(scale,scale);
        if(editing)drawEditBackdrop(canvas);
        for(Control c:controls)drawControl(canvas,c);
        if(editing) {
            float w=getWidth()/scale;
            drawToolbarButton(canvas,new RectF(w/2-250,18,w/2-150,58),"RESET");
            drawToolbarButton(canvas,new RectF(w/2-142,18,w/2-102,58),"−");
            drawToolbarButton(canvas,new RectF(w/2-94,18,w/2-54,58),"+");
            drawToolbarButton(canvas,new RectF(w/2-46,18,w/2+58,58),"SAVE");
            drawToolbarButton(canvas,new RectF(w/2+66,18,w/2+170,58),"CANCEL");
            drawToolbarButton(canvas,new RectF(w/2-65,70,w/2+65,106),"OPTIONS");
        }
        canvas.restore();
        if(layer>=0)canvas.restoreToCount(layer);
    }
        // ---- Neon-glass skin ---------------------------------------------------------------------------------

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
    private static int withAlpha(int rgb, int a) { return (a << 24) | (rgb & 0x00FFFFFF); }
    /** Colour of a pressed control: electric blue for NOS, the accent orange for everything else. */
    private int hotColor(Control c) { return racing && c.scan == 0x38 ? NITRO : ACCENT; }

    /** Gradients depend only on the control's rectangle, so they are built once and rebuilt only after a move or resize. */
    private void buildShaders(Control c) {
        float key = c.area.left * 3.1f + c.area.top * 5.3f + c.area.width() * 7.7f + c.area.height() * 11.1f;
        if (c.ring != null && key == c.key) return;
        c.key = key;
        c.ring = new LinearGradient(c.area.left, c.area.top, c.area.right, c.area.bottom, RING_A, RING_B, Shader.TileMode.CLAMP);
    }
    private void glowRing(Canvas canvas, float cx, float cy, float r, int color) {
        for (int i = 3; i >= 1; --i) {
            line.setColor(withAlpha(color, 18 + (4 - i) * 14));
            line.setStrokeWidth(i * 5f);
            canvas.drawCircle(cx, cy, r, line);
        }
    }
    private void glowOutline(Canvas canvas, RectF a, float corner, int color) {
        for (int i = 3; i >= 1; --i) {
            line.setColor(withAlpha(color, 18 + (4 - i) * 14));
            line.setStrokeWidth(i * 5f);
            canvas.drawRoundRect(a, corner, corner, line);
        }
    }
    private void glowCircle(Canvas canvas, float cx, float cy, float r, int color) {
        for (int i = 3; i >= 1; --i) {
            fill.setColor(withAlpha(color, 22 + (4 - i) * 10));
            canvas.drawCircle(cx, cy, r + r * .12f * i, fill);
        }
    }
    private void glowRect(Canvas canvas, RectF a, float corner, int color) {
        for (int i = 3; i >= 1; --i) {
            float g = i * 4;
            box.set(a.left - g, a.top - g, a.right + g, a.bottom + g);
            fill.setColor(withAlpha(color, 22 + (4 - i) * 10));
            canvas.drawRoundRect(box, corner + g, corner + g, fill);
        }
    }
    private void selectionOutline(Canvas canvas, RectF a, float corner) {
        box.set(a.left - 5, a.top - 5, a.right + 5, a.bottom + 5);
        line.setPathEffect(dash); line.setColor(0xFFFFCF69); line.setStrokeWidth(2f);
        canvas.drawRoundRect(box, corner + 5, corner + 5, line);
        line.setPathEffect(null);
    }
    private void drawEditBackdrop(Canvas canvas) {
        canvas.drawColor(0x66060B12);
        float w = getWidth() / scale, h = getHeight() / scale;
        fill.setColor(0x33FFFFFF);
        for (float x = 20; x < w; x += 40) for (float y = 20; y < h; y += 40) canvas.drawCircle(x, y, 1.4f, fill);
    }

    private void drawControl(Canvas canvas, Control c) {
        boolean on = c.held > 0 || (c.shape == PEDAL && c.scan == 0xc8 && gasWanted());
        boolean picked = editing && c == selected;
        buildShaders(c);
        float cx = c.area.centerX(), cy = c.area.centerY(), r = Math.min(c.area.width(), c.area.height()) / 2;
        int hot = hotColor(c);
        canvas.save();
        if (on) canvas.scale(.95f, .95f, cx, cy);   // pressed: sink slightly
        if (c.shape == PEDAL) drawPedal(canvas, c, on, picked, hot);
        else if (c.shape == WHEEL) drawStick(canvas, c, cx, cy, r, on, picked, hot);
        else drawRound(canvas, c, cx, cy, r, on, picked, hot);
        canvas.restore();
    }
    private void drawRound(Canvas canvas, Control c, float cx, float cy, float r, boolean on, boolean picked, int hot) {
        float stroke = Math.max(2f, r * .07f), rr = r - stroke / 2;
        if (on) glowRing(canvas, cx, cy, rr, hot);
        fill.setColor(on ? withAlpha(hot, 0x47) : 0x2E000000);   // barely-there tint keeps icons readable
        canvas.drawCircle(cx, cy, r, fill);
        int face = faceColor(c);
        line.setStrokeWidth(stroke);
        if (on) line.setColor(hot);
        else if (face != 0) line.setColor(face);
        else line.setShader(c.ring);
        canvas.drawCircle(cx, cy, rr, line);
        line.setShader(null);
        int ink = on ? hot : 0xF2FFFFFF;
        line.setColor(ink); fill.setColor(ink); line.setStrokeWidth(Math.max(2.2f, r * .1f));
        glyphHot = hot;
        drawIcon(canvas, c, cx, cy, r, on);
        if (picked) selectionOutline(canvas, c.area, r);
    }
    private void drawPedal(Canvas canvas, Control c, boolean on, boolean picked, int hot) {
        RectF a = c.area;
        float w = a.width(), h = a.height(), corner = w * .3f, cx = a.centerX(), stroke = Math.max(2f, w * .045f);
        if (on) glowOutline(canvas, a, corner, hot);
        fill.setColor(on ? withAlpha(hot, 0x47) : withAlpha(c.color, 0x40));   // faint green / red tint
        canvas.drawRoundRect(a, corner, corner, fill);
        box.set(a.left + stroke / 2, a.top + stroke / 2, a.right - stroke / 2, a.bottom - stroke / 2);
        line.setStrokeWidth(stroke);
        if (on) line.setColor(hot); else line.setShader(c.ring);
        canvas.drawRoundRect(box, corner, corner, line);
        line.setShader(null);
        line.setStrokeWidth(Math.max(1.4f, w * .03f));                                   // grip ridges
        line.setColor(on ? withAlpha(hot, 0xAA) : 0x40FFFFFF);
        float span = w * .26f;
        for (int i = 0; i < 4; ++i) { float y = a.top + h * (.55f + i * .1f); canvas.drawLine(cx - span, y, cx + span, y, line); }
        line.setColor(on ? hot : 0xF2FFFFFF); line.setStrokeWidth(Math.max(2.4f, w * .06f));
        chevron(canvas, cx, a.top + h * .27f, w * .22f, c.icon.equals("gas"));
        if (picked) selectionOutline(canvas, a, corner);
    }
    private void drawStick(Canvas canvas, Control c, float cx, float cy, float r, boolean on, boolean picked, int hot) {
        float stroke = Math.max(2f, r * .04f);
        fill.setColor(0x2E000000); canvas.drawCircle(cx, cy, r, fill);
        line.setStrokeWidth(stroke);
        line.setShader(c.ring); canvas.drawCircle(cx, cy, r - stroke / 2, line); line.setShader(null);
        float s = c.steering;                                                            // ticks light up toward the steering side
        line.setStrokeWidth(Math.max(1.6f, r * .03f));
        for (int i = 0; i < 24; ++i) {
            boolean lit = s != 0 && COS[i] * Math.signum(s) > .15f && Math.abs(COS[i]) <= Math.abs(s) + .2f;
            line.setColor(lit ? hot : 0x4DFFFFFF);
            canvas.drawLine(cx + COS[i] * r * .76f, cy + SIN[i] * r * .76f, cx + COS[i] * r * .9f, cy + SIN[i] * r * .9f, line);
        }
        float knob = r * .4f, kx = cx + s * (r - knob);
        if (on) glowRing(canvas, kx, cy, knob, hot);
        fill.setColor(on ? withAlpha(hot, 0x47) : 0x2EFFFFFF);
        canvas.drawCircle(kx, cy, knob, fill);
        line.setStrokeWidth(Math.max(2f, r * .05f)); line.setColor(on ? hot : 0xF2FFFFFF);
        canvas.drawCircle(kx, cy, knob, line);
        line.setStrokeWidth(Math.max(1.6f, r * .04f)); line.setColor(0x80FFFFFF);
        chevronSide(canvas, cx - r * .55f, cy, r * .09f, true); chevronSide(canvas, cx + r * .55f, cy, r * .09f, false);
        if (picked) selectionOutline(canvas, c.area, r);
    }
    private int faceColor(Control c) {
        if(!racing||style.equals("classic")||c.icon.length()!=1)return 0;
        if(c.scan==0x39)return style.equals("xbox")?0xff74df8e:0xff80b8ff;
        if(c.scan==0x38)return 0xffff7f86;
        if(c.scan==0x2e)return style.equals("xbox")?0xff80b8ff:0xffdd9cf4;
        return style.equals("xbox")?0xffffd778:0xff74dfb3;
    }
    private void drawIcon(Canvas canvas,Control c,float cx,float cy,float r,boolean on) {
        float s=r*.42f;
        switch(c.icon) {
            case "up": chevron(canvas,cx,cy,s,true);break;
            case "down": chevron(canvas,cx,cy,s,false);break;
            case "left": chevronSide(canvas,cx,cy,s,true);break;
            case "right": chevronSide(canvas,cx,cy,s,false);break;
            case "shiftUp": chevron(canvas,cx,cy-s*.3f,s*.8f,true);chevron(canvas,cx,cy+s*.35f,s*.8f,true);break;
            case "shiftDown": chevron(canvas,cx,cy-s*.35f,s*.8f,false);chevron(canvas,cx,cy+s*.3f,s*.8f,false);break;
            case "pause":
                canvas.drawRoundRect(cx-s*.6f,cy-s*.7f,cx-s*.18f,cy+s*.7f,s*.12f,s*.12f,fill);
                canvas.drawRoundRect(cx+s*.18f,cy-s*.7f,cx+s*.6f,cy+s*.7f,s*.12f,s*.12f,fill);break;
            case "settings":
                for(int i=-1;i<=1;++i){float y=cy+i*s*.62f,k=cx+(i==0?s*.35f:-s*.35f);
                    canvas.drawLine(cx-s*.85f,y,cx+s*.85f,y,line);canvas.drawCircle(k,y,s*.22f,fill);}break;
            case "menu":
                for(int i=0;i<4;++i){float x=cx+(i%2==0?-s*.5f:s*.5f),y=cy+(i<2?-s*.5f:s*.5f);
                    canvas.drawRoundRect(x-s*.36f,y-s*.36f,x+s*.36f,y+s*.36f,s*.1f,s*.1f,fill);}break;
            case "wheel":
                canvas.drawCircle(cx,cy,s*.9f,line);canvas.drawCircle(cx,cy,s*.2f,fill);
                canvas.drawLine(cx-s*.9f,cy,cx+s*.9f,cy,line);canvas.drawLine(cx,cy,cx,cy+s*.9f,line);break;
            case "camera":
                canvas.drawRoundRect(cx-s*.85f,cy-s*.45f,cx+s*.85f,cy+s*.6f,s*.2f,s*.2f,line);
                canvas.drawRect(cx-s*.35f,cy-s*.7f,cx+s*.1f,cy-s*.45f,fill);canvas.drawCircle(cx,cy+s*.08f,s*.3f,line);break;
            case "handbrake":
                canvas.drawCircle(cx,cy,s*.62f,line);
                canvas.drawArc(cx-s*.95f,cy-s*.95f,cx+s*.95f,cy+s*.95f,130,100,false,line);
                canvas.drawArc(cx-s*.95f,cy-s*.95f,cx+s*.95f,cy+s*.95f,-50,100,false,line);
                glyph(canvas,"P",cx,cy,s*.9f,on);break;
            case "nitro": glyph(canvas,"N₂O",cx,cy,s*.9f,on);break;
            case "accept":
                path.reset();path.moveTo(cx-s*.7f,cy);path.lineTo(cx-s*.15f,cy+s*.55f);path.lineTo(cx+s*.75f,cy-s*.5f);
                canvas.drawPath(path,line);break;
            case "map":
                path.reset();path.moveTo(cx-s*.9f,cy-s*.55f);path.lineTo(cx-s*.3f,cy-s*.8f);path.lineTo(cx+s*.3f,cy-s*.55f);
                path.lineTo(cx+s*.9f,cy-s*.8f);path.lineTo(cx+s*.9f,cy+s*.55f);path.lineTo(cx+s*.3f,cy+s*.8f);
                path.lineTo(cx-s*.3f,cy+s*.55f);path.lineTo(cx-s*.9f,cy+s*.8f);path.close();canvas.drawPath(path,line);
                canvas.drawLine(cx-s*.3f,cy-s*.8f,cx-s*.3f,cy+s*.55f,line);canvas.drawLine(cx+s*.3f,cy-s*.55f,cx+s*.3f,cy+s*.8f,line);break;
            case "sms":
                canvas.drawRoundRect(cx-s*.9f,cy-s*.7f,cx+s*.9f,cy+s*.4f,s*.25f,s*.25f,line);
                path.reset();path.moveTo(cx-s*.45f,cy+s*.4f);path.lineTo(cx-s*.6f,cy+s*.85f);path.lineTo(cx-s*.05f,cy+s*.4f);
                canvas.drawPath(path,line);
                for(int dot=-1;dot<=1;++dot)canvas.drawCircle(cx+dot*s*.4f,cy-s*.15f,s*.1f,fill);break;
            case "reset":
                canvas.drawArc(cx-s*.75f,cy-s*.75f,cx+s*.75f,cy+s*.75f,-60,290,false,line);
                path.reset();path.moveTo(cx+s*.2f,cy-s*.95f);path.lineTo(cx+s*.55f,cy-s*.62f);path.lineTo(cx+s*.08f,cy-s*.42f);
                canvas.drawPath(path,line);break;
            case "back":
                canvas.drawLine(cx-s*.5f,cy-s*.5f,cx+s*.5f,cy+s*.5f,line);canvas.drawLine(cx+s*.5f,cy-s*.5f,cx-s*.5f,cy+s*.5f,line);break;
            case "keyboard":
                canvas.drawRoundRect(cx-s*.95f,cy-s*.6f,cx+s*.95f,cy+s*.6f,s*.15f,s*.15f,line);
                for(int row=0;row<2;++row)for(int col=0;col<4;++col)
                    canvas.drawCircle(cx-s*.54f+col*s*.36f,cy-s*.22f+row*s*.3f,s*.07f,fill);
                canvas.drawLine(cx-s*.4f,cy+s*.34f,cx+s*.4f,cy+s*.34f,line);break;
            default: {
                int color=faceColor(c);
                glyph(canvas,c.icon,cx,cy,r*(c.icon.length()==1?1.0f:.6f),on,color!=0&&!on?color:0);
            }
        }
    }
    private void chevron(Canvas canvas,float x,float y,float s,boolean up) {
        float d=up?-1:1;path.reset();path.moveTo(x-s,y-d*s*.45f);path.lineTo(x,y+d*s*.45f);path.lineTo(x+s,y-d*s*.45f);
        canvas.drawPath(path,line);
    }
    private void chevronSide(Canvas canvas,float x,float y,float s,boolean left) {
        float d=left?-1:1;path.reset();path.moveTo(x-d*s*.45f,y-s);path.lineTo(x+d*s*.45f,y);path.lineTo(x-d*s*.45f,y+s);
        canvas.drawPath(path,line);
    }
    private void glyph(Canvas canvas,String value,float x,float y,float size,boolean on) {glyph(canvas,value,x,y,size,on,0);}
    private void glyph(Canvas canvas,String value,float x,float y,float size,boolean on,int color) {
                text.setTextSize(size);text.setColor(color!=0?color:on?glyphHot:0xF0FFFFFF);
        canvas.drawText(value,x,y-(text.ascent()+text.descent())/2,text);
    }
        private void drawToolbarButton(Canvas canvas, RectF area, String label) {
        float round = area.height() / 2;
        fill.setColor(0x80000000); canvas.drawRoundRect(area, round, round, fill);
        line.setShader(null); line.setStrokeWidth(2f); line.setColor(ACCENT);
        canvas.drawRoundRect(area, round, round, line);
        text.setColor(ACCENT); text.setTextSize(label.length() > 2 ? 12 : 24);
        canvas.drawText(label, area.centerX(), area.centerY() - (text.ascent() + text.descent()) / 2, text);
    }

    // ---- Editor ------------------------------------------------------------------------------------------

    private void resizeSelected(float change) {
        if(selected==null)return;
        float factor=Math.max(.55f,Math.min(2.5f,selected.area.width()/selected.original.width()+change));
        float cx=selected.area.centerX(),cy=selected.area.centerY();
        float w=selected.original.width()*factor,h=selected.original.height()*factor;
        selected.area.set(cx-w/2,cy-h/2,cx+w/2,cy+h/2);clamp(selected);
    }
    private void saveLayout() {
        SharedPreferences.Editor save=layouts.edit();
        for(Control c:controls)save.putFloat(c.id+".x",c.area.centerX()/(getWidth()/scale))
                .putFloat(c.id+".y",c.area.centerY()/(getHeight()/scale))
                .putFloat(c.id+".size",c.area.width()/c.original.width()/globalScale);
        save.apply();
    }
    private boolean editTouch(MotionEvent e) {
        int action=e.getActionMasked(),index=e.getActionIndex();
        float x=e.getX(index)/scale,y=e.getY(index)/scale,w=getWidth()/scale;
        if(action==MotionEvent.ACTION_DOWN) {
            if(x>=w/2-65&&x<=w/2+65&&y>=70&&y<=106) {showOptions();return true;}
            if(y>=18&&y<=58) {
                boolean handled=true;
                if(x>=w/2-250&&x<=w/2-150) {for(Control c:controls)c.area.set(c.original);selected=null;}
                else if(x>=w/2-142&&x<=w/2-102)resizeSelected(-.1f);
                else if(x>=w/2-94&&x<=w/2-54)resizeSelected(.1f);
                else if(x>=w/2-46&&x<=w/2+58) {saveLayout();editing=false;layoutControls();}
                else if(x>=w/2+66&&x<=w/2+170) {editing=false;layoutControls();}
                else handled=false;
                if(handled) {invalidate();return true;}
            }
            selected=hit(e.getX(index),e.getY(index));
            dragPointer=e.getPointerId(index);dragX=x;dragY=y;
        } else if(action==MotionEvent.ACTION_MOVE&&selected!=null&&dragPointer>=0) {
            int i=e.findPointerIndex(dragPointer);
            if(i>=0) {x=e.getX(i)/scale;y=e.getY(i)/scale;selected.area.offset(x-dragX,y-dragY);clamp(selected);dragX=x;dragY=y;}
        } else if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_UP||
                (action==MotionEvent.ACTION_POINTER_UP&&e.getPointerId(index)==dragPointer))dragPointer=-1;
        invalidate();return true;
    }
    private void showOptions() {
        new AlertDialog.Builder(getContext()).setTitle("Control options")
                .setItems(new String[]{"Style: classic / Xbox / PlayStation","Tilt steering","FPS limit","Save layout with a name","Load custom layout","NOS + gas macro: "+(macroNos?"ON":"OFF"),"Button size & opacity"},(dialog,which)->{
                    if(which==0)chooseStyle();else if(which==1)configureTilt.run();
                    else if(which==2)configureFrameLimit.run();else if(which==3)saveNamedLayout();
                    else if(which==4)loadNamedLayout();else if(which==5)toggleMacro();else showSizeOpacity();
                }).setNegativeButton("Close",null).show();
    }
    private static SeekBar.OnSeekBarChangeListener onChange(Runnable r) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b,int p,boolean user){r.run();}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        };
    }
    private void showSizeOpacity() {
        Context ctx=getContext();
        LinearLayout form=new LinearLayout(ctx);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(48,24,48,0);
        TextView sizeLabel=new TextView(ctx),opacityLabel=new TextView(ctx);
        SeekBar sizeBar=new SeekBar(ctx),opacityBar=new SeekBar(ctx);
        sizeBar.setMax(90);sizeBar.setProgress(Math.round(globalScale*100)-60);
        opacityBar.setMax(80);opacityBar.setProgress(globalOpacity-20);
        Runnable update=()->{
            sizeLabel.setText("Button size: "+(sizeBar.getProgress()+60)+"%");
            opacityLabel.setText("Opacity: "+(opacityBar.getProgress()+20)+"%");
        };
        sizeBar.setOnSeekBarChangeListener(onChange(update));opacityBar.setOnSeekBarChangeListener(onChange(update));
        update.run();
        form.addView(sizeLabel);form.addView(sizeBar);form.addView(opacityLabel);form.addView(opacityBar);
        new AlertDialog.Builder(ctx).setTitle("Button size & opacity").setView(form)
                .setPositiveButton("Apply",(d,w)->applySizeOpacity((sizeBar.getProgress()+60)/100f,opacityBar.getProgress()+20))
                .setNeutralButton("Reset",(d,w)->applySizeOpacity(1f,100))
                .setNegativeButton("Cancel",null).show();
    }
    private void applySizeOpacity(float newScale,int newOpacity) {
        globalScale=newScale;globalOpacity=newOpacity;
        layouts.edit().putFloat("global_scale",globalScale).putInt("global_opacity",globalOpacity).apply();
        releaseAll();layoutControls();invalidate();
    }
    private void toggleMacro() {
        releaseAll();macroNos=!macroNos;layouts.edit().putBoolean("macro_nos",macroNos).apply();
        Toast.makeText(getContext(),macroNos?"Macro on: pressing NOS also presses gas":"Macro off",Toast.LENGTH_SHORT).show();
    }
    private void chooseStyle() {
        String[] ids={"classic","xbox","playstation"};
        new AlertDialog.Builder(getContext()).setTitle("Driving style")
                .setSingleChoiceItems(new String[]{"Classic · arrows and pedals","Xbox · wheel and A / B / X / Y","PlayStation · wheel and × / ○ / □ / △"},style.equals("classic")?0:style.equals("xbox")?1:2,(dialog,which)->{
                    style=ids[which];layouts.edit().putString("style",style).apply();
                    layoutControls();dialog.dismiss();
                }).setNegativeButton("Cancel",null).show();
    }
    private void saveNamedLayout() {
        EditText name=new EditText(getContext());name.setSingleLine();name.setHint("Layout name");
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle("Save layout "+(racing?"driving":"menu"))
                .setView(name).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=name.getText().toString().trim();if(value.isEmpty()) {name.setError("Enter a name");return;}
            try {
                JSONObject data=new JSONObject();data.put("style",style);
                for(Control c:controls) {JSONObject item=new JSONObject();
                    item.put("x",c.area.centerX()/(getWidth()/scale));item.put("y",c.area.centerY()/(getHeight()/scale));
                    item.put("size",c.area.width()/c.original.width()/globalScale);data.put(c.id,item);}
                layouts.edit().putString("preset."+(racing?"race.":"menu.")+value,data.toString()).apply();
                saveLayout();Toast.makeText(getContext(),"Layout saved: "+value,Toast.LENGTH_SHORT).show();dialog.dismiss();
            }catch(org.json.JSONException exception) {name.setError("Failed to save layout");}
        }));dialog.show();
    }
    private void loadNamedLayout() {
        String prefix="preset."+(racing?"race.":"menu.");ArrayList<String> names=new ArrayList<>();
        for(String key:layouts.getAll().keySet())if(key.startsWith(prefix))names.add(key.substring(prefix.length()));
        java.util.Collections.sort(names);
        if(names.isEmpty()) {Toast.makeText(getContext(),"No saved layouts for this mode yet",Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(getContext()).setTitle("Load layout").setItems(names.toArray(new String[0]),(dialog,which)->{
            try {
                JSONObject data=new JSONObject(layouts.getString(prefix+names.get(which),"{}"));
                style=data.getString("style");SharedPreferences.Editor save=layouts.edit().putString("style",style);
                java.util.Iterator<String> ids=data.keys();while(ids.hasNext()) {String id=ids.next();if(id.equals("style"))continue;
                    JSONObject item=data.getJSONObject(id);save.putFloat(id+".x",(float)item.getDouble("x"))
                            .putFloat(id+".y",(float)item.getDouble("y")).putFloat(id+".size",(float)item.getDouble("size"));}
                save.apply();layoutControls();
            }catch(org.json.JSONException exception) {Toast.makeText(getContext(),"Failed to load layout",Toast.LENGTH_SHORT).show();}
        }).setNegativeButton("Cancel",null).show();
    }

    // ---- Input -------------------------------------------------------------------------------------------

    /** The control under a point; round controls use their circle, with a little slack. */
    private Control hit(float x,float y) {
        x/=scale;y/=scale;Control best=null;double bestDistance=Double.MAX_VALUE;
        for(Control c:controls) {
            float cx=c.area.centerX(),cy=c.area.centerY();
            double d;
            if(c.shape==PEDAL) {
                if(Math.abs(x-cx)>c.area.width()*.56f||Math.abs(y-cy)>c.area.height()*.56f)continue;
                d=Math.hypot((x-cx)/c.area.width(),(y-cy)/c.area.height());
            } else {
                float r=Math.min(c.area.width(),c.area.height())/2;
                d=Math.hypot(x-cx,y-cy)/r;if(d>(c.shape==WHEEL?1.15:1.12))continue;
            }
            if(d<bestDistance){bestDistance=d;best=c;}
        }
        return best;
    }
    private void hold(Control c) {
        if(c==null||++c.held!=1)return;
        if(c.scan>=0)keys.set(c.scan,true);
        if(racing&&(c.scan==0xc8||c.scan==0x38))keys.set(0xc8,gasWanted());
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }
    /** Gas harus tertekan jika pedal gas ditahan, atau (makro aktif) tombol NOS ditahan. */
    private boolean gasWanted() {
        for(Control c:controls) if(c.shape==PEDAL&&c.scan==0xc8&&c.held>0) return true;
        if(macroNos&&racing) for(Control c:controls) if(c.scan==0x38&&c.held>0) return true;
        return false;
    }
    private void release(Control c) { if(c!=null && --c.held==0) {
        if(c.scan>=0)keys.set(c.scan,false);
        else if(c.scan==STICK) {c.steering=0;keys.set(0xcb,false);keys.set(0xcd,false);}
        if(racing&&(c.scan==0xc8||c.scan==0x38))keys.set(0xc8,gasWanted());
    } }
    private void steer(Control c,float x) {
        c.steering=Math.max(-1,Math.min(1,(x/scale-c.area.centerX())/(c.area.width()/2)));
        keys.set(0xcb,c.steering<-.18f);keys.set(0xcd,c.steering>.18f);
    }
    void setPointer(Pointer pointer) { this.pointer=pointer; }
    private void pointerTouch(float x,float y,int phase) {
        if(pointer==null||getWidth()==0||getHeight()==0)return;
        pointerX=x;pointerY=y;pointer.touch(x/getWidth(),y/getHeight(),phase);
    }
    private void pointerRelease() { if(pointerId>=0){pointerId=-1;pointerTouch(pointerX,pointerY,2);} }
    /** Releases fingers the event no longer carries (their ACTION_POINTER_UP never arrived). */
    private void releaseLiftedFingers(MotionEvent e) {
        java.util.Iterator<java.util.Map.Entry<Integer,Control>> it=fingers.entrySet().iterator();
        while(it.hasNext()) {
            java.util.Map.Entry<Integer,Control> finger=it.next();
            if(e.findPointerIndex(finger.getKey())<0){release(finger.getValue());it.remove();}
        }
        if(pointerId>=0&&e.findPointerIndex(pointerId)<0)pointerRelease();
    }
    void releaseAll() { for(Control c:fingers.values()) release(c); fingers.clear(); pointerRelease(); invalidate(); }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(editing)return editTouch(e);
        int action=e.getActionMasked(),index=e.getActionIndex(),id=e.getPointerId(index);
        if(hiddenByGamepad) {
            if(action==MotionEvent.ACTION_DOWN){hiddenByGamepad=false;invalidate();}
            return true;
        }
        if(action==MotionEvent.ACTION_CANCEL) { releaseAll(); return true; }
        // A new gesture means no finger is down: anything still held lost its
        // ACTION_UP (edge gestures, palm rejection, overlays) and would stay
        // pressed forever, e.g. the left arrow steering by itself.
        if(action==MotionEvent.ACTION_DOWN)releaseAll();
        else releaseLiftedFingers(e);
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN) {
            Control c=hit(e.getX(index),e.getY(index));if(c!=null&&c.scan==STICK&&c.held>0)c=null;
            if(c==null&&pointerId<0&&hit(e.getX(index),e.getY(index))==null){pointerId=id;pointerTouch(e.getX(index),e.getY(index),0);}
            release(fingers.put(id,c));hold(c);if(c!=null&&c.scan==STICK)steer(c,e.getX(index));
        } else if(action==MotionEvent.ACTION_MOVE) {
            for(int i=0;i<e.getPointerCount();++i) {
                int pointer=e.getPointerId(i);
                if(pointer==pointerId){pointerTouch(e.getX(i),e.getY(i),1);continue;}
                Control old=fingers.get(pointer),next=hit(e.getX(i),e.getY(i));
                if(old!=null&&old.scan==STICK) {steer(old,e.getX(i));continue;}
                // Menu-style buttons do not slide; pedals and arrows follow the thumb.
                if(old!=null&&old.scan<0)continue;
                if(next!=null&&(next.scan==STICK&&next.held>0||next.scan<0&&next.scan!=STICK))next=null;
                if(old!=next) {release(old);fingers.put(pointer,next);hold(next);if(next!=null&&next.scan==STICK)steer(next,e.getX(i));}
            }
        } else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP) {
            if(id==pointerId){pointerId=-1;pointerTouch(e.getX(index),e.getY(index),2);}
            Control c=fingers.remove(id); release(c);
            if(c!=null && c==hit(e.getX(index),e.getY(index))) {
                if(c.scan==MODE) {racing=!racing;layoutControls();}
                else if(c.scan==TEXT) {releaseAll();textInput.run();}
                else if(c.scan==EDIT) {releaseAll();editing=true;selected=null;}
                performClick();
            }
            if(action==MotionEvent.ACTION_UP)releaseAll();  // the last finger left
        }
        invalidate();return true;
    }
    @Override public boolean performClick() { super.performClick();return true; }
}
