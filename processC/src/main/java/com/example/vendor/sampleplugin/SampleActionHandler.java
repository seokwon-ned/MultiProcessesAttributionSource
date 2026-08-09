package com.example.vendor.sampleplugin;

import android.content.Context;
import android.os.Bundle;

import com.example.toolhub.plugin.ParameterValues;
import com.example.toolhub.plugin.ToolHandler;
import com.example.toolhub.plugin.annotation.HandlerPermission;

public class SampleActionHandler implements ToolHandler {

    @HandlerPermission({"android.permission.READ_CONTACTS"})
    @Override
    public Bundle onPerformAction(Context attributionContext, Bundle args, ParameterValues params) {
        Bundle result = new Bundle();
        result.putString("echo", args.getString("query"));
        return result;
    }

    @HandlerPermission({"android.permission.READ_CONTACTS"})
    @Override
    public Bundle onReversePerformAction(Context attributionContext, Bundle args, ParameterValues params) {
        return new Bundle();
    }
}
