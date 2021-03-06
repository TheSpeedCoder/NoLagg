package com.bergerkiller.bukkit.nolagg.examine.segments;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Arrays;

public class DataSegment extends Segment {
    private String plugin;
    private String location;
    private boolean task;

    public DataSegment(DataSegment segment) {
        super(segment);
        this.plugin = segment.plugin;
        this.location = segment.location;
        this.task = segment.task;
    }

    public DataSegment(String name, int duration, SegmentData data, String plugin, String location, boolean task) {
        super(name, duration, Arrays.asList(data));
        this.plugin = plugin;
        this.location = location;
        this.task = task;
    }

    public String getPlugin() {
        return this.plugin;
    }

    public String getLocation() {
        return this.location;
    }

    public boolean isTask() {
        return this.task;
    }

    public boolean isServerOperation() {
        return this.plugin.startsWith("#");
    }

    @Override
    public void export(BufferedWriter writer, int indent) throws IOException {
        super.export(writer, indent);
        if (this.isServerOperation()) {
            export(writer, indent, "Server branch: " + this.getPlugin());
        } else {
            export(writer, indent, "Plugin: " + this.getPlugin());
            export(writer, indent, "Location: " + this.getLocation());
        }
        export(writer, indent, "---------------------------------------------\n");
    }

    @Override
    public String getDescription() {
        StringBuilder builder = new StringBuilder(super.getDescription());
        if (this.isServerOperation()) {
            builder.append('\n').append("Server operation: ").append(this.getName());
            builder.append('\n').append("Server branch: ").append(this.getPlugin());
        } else {
            if (this.isTask()) {
                builder.append('\n').append("Selected task: ").append(this.getName());
            } else {
                builder.append('\n').append("Selected event: ").append(this.getName());
            }
            builder.append('\n').append("Plugin: ").append(this.getPlugin());
            builder.append('\n').append("Location: ");
            int execCount = -1;
            int cancelCount = -1;
            for (String loc : this.getLocation().split("\n")) {
                if (loc.startsWith("Execution count: ")) {
                    try {
                        execCount = Integer.parseInt(loc.substring(17));
                    } catch (NumberFormatException ex) {
                    }
                }
                if (loc.startsWith("Cancelled: ")) {
                    try {
                        cancelCount = Integer.parseInt(loc.substring(11));
                        continue;
                    } catch (NumberFormatException ex) {
                    }
                }
                builder.append(loc).append('\n');
            }
            if (cancelCount != -1) {
                builder.append("Cancelled: ").append(cancelCount);
                if (execCount != -1) {
                    builder.append(" (").append(round(100.0 * (double) cancelCount / (double) execCount, 2)).append("%)");
                }
            }
        }
        return builder.toString();
    }

    public DataSegment clone() {
        return new DataSegment(this);
    }
}
