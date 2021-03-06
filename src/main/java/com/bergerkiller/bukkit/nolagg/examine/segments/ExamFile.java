package com.bergerkiller.bukkit.nolagg.examine.segments;

import com.bergerkiller.bukkit.nolagg.examine.reader.ExamReader;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * Contains the raw information found in a exam file Data will be converted into
 * the required root segment
 */
public class ExamFile extends Segment {

    public final List<DataSegment> events;
    public final MultiPluginSegment multiPlugin;
    public final MultiEventSegment multiEvent;

    public ExamFile(String name, int duration, List<DataSegment> data) {
        super(name, duration, getEmptySegments(duration, data));
        this.events = data;
        // generate multi-plugin and multi-event based segments
        this.multiPlugin = MultiPluginSegment.create(duration, data);
        this.multiEvent = MultiEventSegment.create(duration, this.multiPlugin.getPluginCount(), data);
        this.multiPlugin.setParent(this);
        this.multiEvent.setParent(this);
    }

    public static List<SegmentData> getEmptySegments(int duration, List<DataSegment> data) {
        ArrayList<SegmentData> rval = new ArrayList<SegmentData>();
        rval.add(new SegmentData("Plugin view", duration));
        rval.add(new SegmentData("Event view", duration));
        SegmentData first = rval.get(0);
        // prepare a monotone graph
        SegmentData[] merged = new SegmentData[data.size()];
        for (int i = 0; i < merged.length; i++) {
            merged[i] = data.get(i).getMergedData();
        }
        first.load(merged);
        return rval;
    }

    public static String getPriority(int slot) {
        switch (slot) {
            case 0:
                return "LOWEST";
            case 1:
                return "LOW";
            case 3:
                return "HIGH";
            case 4:
                return "HIGHEST";
            case 5:
                return "MONITOR";
            default:
                return "NORMAL";
        }
    }

    public static ExamFile read(File file) {
        ExamFile efile = null;
        try {
            DataInputStream stream = new DataInputStream(new InflaterInputStream(new FileInputStream(file)));
            try {
                try {
                    efile = read(stream);
                } catch (ZipException ex) {
                    stream.close();
                    stream = new DataInputStream(new FileInputStream(file));
                    efile = read(stream);
                }
            } catch (Exception ex) {
                ExamReader.msgbox("Failed to load file: \n\n" + ex.toString());
                ex.printStackTrace();
            } finally {
                stream.close();
            }
        } catch (Throwable ex) {
            ExamReader.msgbox("Failed to load file: \n\n" + ex.toString());
            ex.printStackTrace();
        }
        return efile;
    }

    private static String readLocation(DataInputStream stream, String plugin, String name) throws IOException {
        int loccount = stream.readInt();
        StringBuilder location = new StringBuilder(loccount * 300);
        if (!plugin.startsWith("#")) {
            location.append(name);
        }
        for (int j = 0; j < loccount; j++) {
            location.append('\n').append(stream.readUTF());
        }
        return location.toString();
    }

    public static ExamFile read(DataInputStream stream) throws IOException {
        final int listenercount = stream.readInt();
        final int duration = stream.readInt();
        List<DataSegment> segments = new ArrayList<DataSegment>();
        // Read all listeners
        for (int i = 0; i < listenercount; i++) {
            if (stream.readBoolean()) {
                String plugin = stream.readUTF();
                String name = stream.readUTF() + "[" + getPriority(stream.readInt()) + "]";
                String loc = stream.readUTF();

                SegmentData data = new SegmentData(name, duration);
                data.readLongValues(stream);
                segments.add(new DataSegment(name, duration, data, plugin, loc, false));
            }
        }
        // Read all tasks
        final int taskcount = stream.readInt();
        for (int i = 1; i <= taskcount; i++) {
            // Read the task location name
            String name = stream.readUTF();
            // Next-tick tasks append this to the name to identify themselves
            boolean nextTick = name.startsWith("[NextTick] ");
            if (nextTick) {
                name = name.substring(11);
            }

            // Read plugin name and location
            String plugin = stream.readUTF();
            String location = readLocation(stream, plugin, name);

            final String segname;
            if (plugin.startsWith("#")) {
                // Server operation: use location as name
                segname = location.trim();
            } else if (nextTick) {
                // Next tick: append next tick to name
                segname = "NextTick Task #" + i;
            } else {
                // Regular task
                segname = "Task #" + i;
            }

            // Initialize the new data segment
            SegmentData data = new SegmentData(name, duration);
            data.readLongValues(stream);
            segments.add(new DataSegment(segname, duration, data, plugin, location, true));
        }
        return new ExamFile("Results", duration, segments);
    }

    public Segment getSegment(int index) {
        if (index == 0) {
            return this.multiPlugin;
        } else if (index == 1) {
            return this.multiEvent;
        } else {
            return null;
        }
    }

    @Override
    public String getDescription() {
        StringBuilder builder = new StringBuilder(super.getDescription());
        builder.append('\n').append('\n');
        builder.append("Click one of the two view hierarchy modes above.");
        builder.append("\n\nPlugin view: Compare plugins, then \nevents and tasks in these plugins");
        builder.append("\n\nEvent view: Compare events and tasks, then \nlook at the plugins");
        return builder.toString();
    }

    @Override
    public void export(BufferedWriter writer, int indent) throws IOException {
        this.multiPlugin.export(writer, indent);
    }
}
