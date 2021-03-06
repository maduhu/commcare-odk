package org.odk.collect.android.logic;

import android.graphics.drawable.Drawable;

import org.javarosa.core.model.FormIndex;

import java.util.ArrayList;

public class HierarchyElement {
    private String mPrimaryText = "";
    private String mSecondaryText = "";
    private Drawable mIcon;
    private int mColor;
    HierarchyEntryType mType;
    FormIndex mFormIndex;
    ArrayList<HierarchyElement> mChildren;

    public HierarchyElement(String text1, String text2, Drawable bullet, int color, HierarchyEntryType type,
                            FormIndex f) {
        mIcon = bullet;
        mPrimaryText = text1;
        mSecondaryText = text2;
        mColor = color;
        mFormIndex = f;
        mType = type;
        mChildren = new ArrayList<>();
    }

    public String getPrimaryText() {
        return mPrimaryText;
    }

    public String getSecondaryText() {
        return mSecondaryText;
    }

    public void setIcon(Drawable icon) {
        mIcon = icon;
    }

    public Drawable getIcon() {
        return mIcon;
    }

    public FormIndex getFormIndex() {
        return mFormIndex;
    }

    public HierarchyEntryType getType() {
        return mType;
    }

    public void setType(HierarchyEntryType newType) {
        mType = newType;
    }

    public ArrayList<HierarchyElement> getChildren() {
        return mChildren;
    }

    public void addChild(HierarchyElement h) {
        mChildren.add(h);
    }

    public void setColor(int color) {
        mColor = color;
    }

    public int getColor() {
        return mColor;
    }
}
