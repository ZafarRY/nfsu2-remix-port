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
        c.area.set(cx-