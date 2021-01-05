package edu.illinois.cs.diaper;

import com.thoughtworks.xstream.converters.collections.MapConverter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.io.ExtendedHierarchicalStreamWriterHelper;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.Mapper;

import java.util.Iterator;
import java.util.Map;

public class CustomMapConverter extends MapConverter {

    public CustomMapConverter(Mapper mapper) {
        super(mapper);
    }

    @Override
    public void marshal(final Object source, final HierarchicalStreamWriter writer, final MarshallingContext context) {
        Map map = (Map) source;
        String entryName = mapper().serializedClass(Map.Entry.class);
        for (Iterator iterator = map.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry entry = (Map.Entry) iterator.next();
            ExtendedHierarchicalStreamWriterHelper.startNode(writer, entryName, entry.getClass());
            writer.addAttribute("key", entry.getKey().toString());
            writer.addAttribute("value", entry.getValue().toString());

            writeItem(entry.getKey(), context, writer);
            writeItem(entry.getValue(), context, writer);

            writer.endNode();
        }
    }
}
