package org.bimserver.serializers.json;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import org.bimserver.emf.IdEObject;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.emf.PackageMetaData;
import org.bimserver.models.geometry.GeometryData;
import org.bimserver.models.geometry.GeometryInfo;
import org.bimserver.models.ifc2x3tc1.IfcColumn;
import org.bimserver.models.ifc2x3tc1.IfcDoor;
import org.bimserver.models.ifc2x3tc1.IfcProduct;
import org.bimserver.models.ifc2x3tc1.IfcSlab;
import org.bimserver.models.ifc2x3tc1.IfcWall;
import org.bimserver.models.ifc2x3tc1.IfcWindow;
import org.bimserver.models.ifc2x3tc1.IfcDistributionControlElement;
import org.bimserver.models.ifc2x3tc1.IfcFurnishingElement;
import org.bimserver.models.ifc2x3tc1.IfcBuildingElementProxy;
import org.bimserver.models.ifc2x3tc1.IfcFlowTerminal;
import org.bimserver.models.ifc2x3tc1.IfcSpace;
import org.bimserver.plugins.PluginManager;
import org.bimserver.plugins.renderengine.RenderEnginePlugin;
import org.bimserver.plugins.serializers.EmfSerializer;
import org.bimserver.plugins.serializers.ProgressReporter;
import org.bimserver.plugins.serializers.ProjectInfo;
import org.bimserver.plugins.serializers.SerializerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThreeJsSerializer extends EmfSerializer {

	private static final Logger LOGGER = LoggerFactory.getLogger(ThreeJsSerializer.class);
	private PrintWriter out;

	public void init(IfcModelInterface model, ProjectInfo projectInfo, PluginManager pluginManager, RenderEnginePlugin renderEnginePlugin, PackageMetaData packageMetaData, boolean oids) throws SerializerException {
		super.init(model, projectInfo, pluginManager, renderEnginePlugin, packageMetaData, false);
	}

	@Override
    public void reset() {
		setMode(Mode.BODY);
	}

	@Override
	protected boolean write(OutputStream outputStream, ProgressReporter progressReporter) {
		if (getMode() == Mode.BODY) {
			out = new PrintWriter(outputStream);
			Map<String, GeometryInfo> geometryData = new HashMap<String, GeometryInfo>();
			Map<String, String> objectTypes = new HashMap<String, String>();
			// A map that holds the material indexes for each object.
			// Each object has a MultiMaterial assigned to it. And the different
			// faces of that object have different materials. These indexes are
			// the material indexes in that MultiMaterial, corresponding to that
			// face. The key to get those material indexes is a long which is
			// holding the color information for that face in form 0xRRGGBBAA.
			//
			// This map is filled when the materials are written, and later is
			// used when writing the geometries.
			//
			// The first key is the oid of the geometryData.
			Map<Long, Map<Long, Integer>> objectMaterialIndexes =
					new HashMap<Long, Map<Long, Integer>>();
			collectObjectData(geometryData, objectTypes);
			out.println("{");
			out.println("  \"metadata\" : { \"formatVersion\" : 4.3, \"type\" : \"object\", \"generator\" : \"BIMserver three.js serializer\"  }, ");
			out.println("  \"materials\" : [");
			writeMaterials(geometryData, objectMaterialIndexes);
			out.println("  ],");
			out.println("  \"geometries\" : [");
			writeGeometries(geometryData, objectMaterialIndexes);
			out.println("  ],");
			out.println("  \"object\" : {");
			out.println("    \"uuid\" : \"root\",");
			out.println("    \"type\" : \"Scene\",");
			out.println("    \"matrix\" : [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1],");
			out.println("    \"children\" : [");
			writeObjects(geometryData, objectTypes);
			out.println("    ]");
			out.println("  }");
			out.println("}");
			out.flush();

			setMode(Mode.FINISHED);
			return true;
		} else {
			return false;
		}
	}
	
	private void writeMaterials(Map<String, GeometryInfo> geometryInfos,
			Map<Long, Map<Long, Integer>> objectMaterialIndexes) {
		boolean first = true;
		Set<Long> writtenMultiMaterials = new HashSet<Long>();
		for(GeometryInfo geometryInfo : geometryInfos.values()) {
			Long id = geometryInfo.getData().getOid();
			if(!writtenMultiMaterials.contains(id)) {
				out.print(first ? "" : ",\n");
				first = false;
				out.println("    {");
				objectMaterialIndexes.put(
						id,
						new HashMap<Long, Integer>()
						);
				writeMultiMaterial(
						geometryInfo.getData(),
						objectMaterialIndexes.get(id)
						);
				out.print("    }");
				writtenMultiMaterials.add(id);
			}
		}
		out.println();
	}
	
	private void writeMultiMaterial(GeometryData geometryData,
			Map<Long, Integer> materialIndexes) {
		// Create a MultiMaterial for this geometry that includes all of the 
		// materials, and add it to the global list of materials
		out.println("      \"uuid\" : \"" + geometryData.getOid() + "M\",");
		out.println("      \"type\" : \"MultiMaterial\", ");
		out.println("      \"materials\": [");
		
		// TODO
		// For now just loop through the face indexes in order to find out what
		// kind of materials we have, and add them to the indexes map.
		List<Integer> indexes = getIntegerList(geometryData.getIndices());
		if(null != indexes && 0 < indexes.size()) {
			// Add the colors as keys
			for(int i = 0; i < indexes.size(); i += 3) {
				long color = this.getFaceColor(geometryData, i);
				materialIndexes.put(color, 0);
			}
			// Now loop through the keys and create the materials, and write
			// them.
			// The counter indicates the index of the material inside the
			// materials array of the MultiMaterial
			int counter = 0;
			for( Map.Entry<Long, Integer> material_entry :
					materialIndexes.entrySet() ) {
				material_entry.setValue(counter);
				// Extract the color values
				long color = material_entry.getKey();
				int rgb_color = (int)(color >> 8);
				int alpha_color = (int)(color & 0x000000ffL);
				float opacity = (float)alpha_color / 255.0f;
				// Now write them
				out.print("        { ");
				out.print("\"type\" : \"MeshPhongMaterial\", ");
				if(opacity < 1.0) {
					out.print("\"transparent\" : true, ");
					out.print("\"opacity\" : \"" + opacity + "\", ");
				}
				out.print("\"color\" : " + rgb_color + "");
				out.println(++counter >= materialIndexes.size() ? " }" : " },");
			}
		}
		
		// End writing materials array
		out.println("      ]");
	}
	
	// Returns the average color for that face in form of 0xRRGGBBAA.
	private long getFaceColor(GeometryData geometryData, int firstVertexIndex) {
		long colorCode = 0x00000000L;
		byte[] b = geometryData.getMaterials();
		// If there is a problem getting the materials for this geometry,
		// just return white color as default.
		if(null == b) {
			return 0xFFFFFFFFL;
		}
		List<Float> colors = getFloatList(b);
		b = geometryData.getIndices();
		if(null == b) {
			return 0xFFFFFFFFL;
		}
		List<Integer> indexes = getIntegerList(b);
		if( null != colors && indexes != null && 
			0 < colors.size() && 0 < indexes.size()) {
			for(int c = 0; c < 4; c++) {
				// Now using these indexes, get the average color of the
				// face. The reason for getting the average is in case the
				// vertices have different color values, although this is
				// unlikely.
				float color_value = (
						colors.get( indexes.get(firstVertexIndex) * 4 + c ) +
						colors.get( indexes.get(firstVertexIndex+1) * 4  + c ) +
						colors.get( indexes.get(firstVertexIndex+2) * 4 + c )
						) / 3.0f;
				// Convert this color value to 0-255 integer, and add it to the
				// long int.
				colorCode += (int)(color_value*255.0f) * Math.pow(16,(3-c)*2);
			}
		}
		return colorCode;
	}

	private void writeGeometry(GeometryData geometryData,
			Map<Long, Integer> materialIndexes) {
		out.println("      \"uuid\" : \"" + geometryData.getOid() + "\", ");
		out.println("      \"type\" : \"Geometry\", ");
		out.println("      \"data\" : {");
		out.print(  "        \"vertices\" : [ ");

		List<Float> vertices = getFloatList(geometryData.getVertices());
		if (vertices != null && vertices.size() > 0) {
			for (int i = 0; i < vertices.size(); i++) {
				out.print(i == 0 ? "" : ",");
				out.print(i % 3 == 0 ? " " : "");
				out.print(vertices.get(i));
			}
		}

		out.println(" ],");
		out.print("        \"normals\" : [ ");

		List<Float> normals = getFloatList(geometryData.getNormals());
		if (normals != null && normals.size() > 0) {
			for (int i = 0; i < normals.size(); i++) {
				out.print(i == 0 ? "" : ",");
				out.print(i % 3 == 0 ? " " : "");
				out.print(normals.get(i));
			}
		}

		out.println(" ],");
		out.print(  "        \"faces\" : [ ");

		List<Integer> indices = getIntegerList(geometryData.getIndices());
		
		if (indices != null && indices.size() > 0) {
			for (int i = 0; i < indices.size(); i += 3) {
				out.print(i == 0 ? "" : ",");
				// 2 = faces have materials assigned to them. Refer to three.js
				// JSON format documentation.
				out.print(" 2, ");
				// Write a face using the indexes of 3 vertices
				out.print((indices.get(i)) + "," + (indices.get(i + 1)) + "," + (indices.get(i + 2)) + ",");
				// Now write the material index for this object.
				long color_code = this.getFaceColor(geometryData, i);
				try {
					out.print( materialIndexes.get(color_code) );
				} catch (IndexOutOfBoundsException e) {
					LOGGER.error("Material information may be corrupted");
					out.print( 0 );
				}
			}
		}

		out.println(" ]");
		out.println("      }");
	}

	@SuppressWarnings("unchecked")
	private boolean collectObjectData(
			Map<String, GeometryInfo> geometryData,
			Map<String, String> objectTypes_
			) {
		Class<IdEObject>[] eClasses = new Class[] {
				IfcWall.class, IfcWindow.class, IfcDoor.class, IfcSlab.class, IfcColumn.class,
				IfcDistributionControlElement.class, IfcFurnishingElement.class,
				IfcBuildingElementProxy.class, IfcFlowTerminal.class, IfcSpace.class,
				org.bimserver.models.ifc4.IfcWall.class,org.bimserver.models.ifc4.IfcWindow.class,
				org.bimserver.models.ifc4.IfcDoor.class, org.bimserver.models.ifc4.IfcSlab.class,
				org.bimserver.models.ifc4.IfcColumn.class,
				org.bimserver.models.ifc4.IfcDistributionControlElement.class,
				org.bimserver.models.ifc4.IfcFurnishingElement.class,
				org.bimserver.models.ifc4.IfcBuildingElementProxy.class,
				org.bimserver.models.ifc4.IfcFlowTerminal.class,
				org.bimserver.models.ifc4.IfcSpace.class
		};
		for (Class<? extends IdEObject> eClass : eClasses) {
			for (IdEObject object : model.getAllWithSubTypes(eClass)) {
				IfcProduct ifcRoot = (IfcProduct) object;
				GeometryInfo geometryInfo = ifcRoot.getGeometry();
				if (geometryInfo != null) {
					geometryData.put(ifcRoot.getGlobalId(), geometryInfo);
				}
				//String objectType = eClass.getSimpleName() + ":" + ifcRoot.getName();
				String objectType = eClass.getSimpleName();
				objectTypes_.put(ifcRoot.getGlobalId(), objectType);
			}
		}
		return true;
	}


	private void writeGeometries(Map<String, GeometryInfo> geometryInfos,
			Map<Long, Map<Long, Integer>> objectMaterialIndexes) {
		boolean first = true;
		Set<Long> writtenGeometries = new HashSet<Long>();
		for(GeometryInfo geometryInfo : geometryInfos.values()) {
			Long objectId = geometryInfo.getData().getOid();
			if(!writtenGeometries.contains(objectId)) {
				out.print(first ? "" : ",\n");
				out.println("    {");
				first = false;
				writeGeometry(
						geometryInfo.getData(),
						objectMaterialIndexes.get(objectId)
						);
				out.print("    }");
				writtenGeometries.add(objectId);
			}
		}
		out.println();
	}

	private void writeObjects(
			Map<String, GeometryInfo> geometryInfos,
			Map<String, String> objectTypes
			) {
		boolean first = true;
		for (Map.Entry<String, GeometryInfo> geometryEntry: geometryInfos.entrySet()) {
			String guid = geometryEntry.getKey();
			GeometryInfo geometryInfo = geometryEntry.getValue();
			String objectType = objectTypes.get(geometryEntry.getKey());
			out.print(first ? "" : ",\n");
			out.println("      {");
			writeObject(guid, geometryInfo, objectType);
			out.print("      }");
			first = false;
		}
		out.println();
	}

	private void writeObject(
			String guid,
			GeometryInfo geometryInfo,
			String type
			) {
		long oid = geometryInfo.getData().getOid();
		out.println("        \"uuid\" : \"" + guid + "\", ");
		out.println("        \"name\" : \"" + type + "\", ");
		out.println("        \"type\" : \"Mesh\", ");
		out.println("        \"material\" : \"" + oid + "M\", ");
		out.println("        \"geometry\" : \"" + oid + "\", ");
		out.print(  "        \"matrix\" : [");
		boolean first = true;
		for(float i: getFloatList(geometryInfo.getTransformation())){
			out.print(first ? "" : ",");
			out.print(i);
			first=false;
		}
		out.println("]");
	}

	private List<Float> getFloatList(byte[] byteArray) {
		ByteBuffer byteBuffer = ByteBuffer.wrap(byteArray);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		List<Float> floatList = new ArrayList<Float>();
		while(byteBuffer.hasRemaining()){
            floatList.add(byteBuffer.getFloat());
        }
		return floatList;
	}
	private List<Integer> getIntegerList(byte[] byteArray) {
		ByteBuffer byteBuffer = ByteBuffer.wrap(byteArray);
		byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
		List<Integer> integerList = new ArrayList<Integer>();
		while(byteBuffer.hasRemaining()){
			integerList.add(byteBuffer.getInt());
		}
		return integerList;
	}

}