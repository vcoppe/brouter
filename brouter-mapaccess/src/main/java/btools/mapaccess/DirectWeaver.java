package btools.mapaccess;

import btools.codec.DataBuffers;
import btools.codec.NoisyDiffCoder;
import btools.codec.StatCoderContext;
import btools.codec.TagValueCoder;
import btools.codec.TagValueValidator;
import btools.codec.TagValueWrapper;
import btools.codec.WaypointMatcher;
import btools.util.ByteDataWriter;
import btools.util.IByteArrayUnifier;

/**
 * DirectWeaver does the same decoding as MicroCache2, but decodes directly
 * into the instance-graph, not into the intermediate nodes-cache
 */
public final class DirectWeaver extends ByteDataWriter
{
  private int lonBase;
  private int latBase;
  private int cellsize;

  private int size = 0;

  private static boolean debug = false;
  
  public DirectWeaver( DataBuffers dataBuffers, int lonIdx, int latIdx, int divisor, TagValueValidator wayValidator, WaypointMatcher waypointMatcher, OsmNodesMap hollowNodes ) throws Exception
  {
    super( null );
    cellsize = 1000000 / divisor;
    lonBase = lonIdx*cellsize;
    latBase = latIdx*cellsize;

    StatCoderContext bc = new StatCoderContext( dataBuffers.iobuffer );

    TagValueCoder wayTagCoder = new TagValueCoder( bc, dataBuffers, wayValidator );
    TagValueCoder nodeTagCoder = new TagValueCoder( bc, dataBuffers, null );
    NoisyDiffCoder nodeIdxDiff = new NoisyDiffCoder( bc );
    NoisyDiffCoder nodeEleDiff = new NoisyDiffCoder( bc );
    NoisyDiffCoder extLonDiff = new NoisyDiffCoder(bc);
    NoisyDiffCoder extLatDiff = new NoisyDiffCoder(bc);
    NoisyDiffCoder transEleDiff = new NoisyDiffCoder( bc );

    size = bc.decodeNoisyNumber( 5 );

    int[] faid = size > dataBuffers.ibuf2.length ? new int[size] : dataBuffers.ibuf2;

    if ( debug ) System.out.println( "*** decoding cache of size=" + size + " for lonIdx=" + lonIdx + " latIdx=" + latIdx );

    bc.decodeSortedArray( faid, 0, size, 0x20000000, 0 );
    
    OsmNode[] nodes = new OsmNode[size];
    for( int n = 0; n<size; n++ )
    {
      long id = expandId( faid[n] );
      int ilon = (int) ( id >> 32 );
      int ilat = (int) ( id & 0xffffffff );
      OsmNode node = hollowNodes.get( ilon, ilat );
      if ( node == null )
      {
        node = new OsmNode( ilon, ilat );
        node.visitID = 0;
      }
      else
      {
        node.visitID = 1;
        hollowNodes.remove( node );
      }
      nodes[n] = node;
    }
    
    int netdatasize = bc.decodeNoisyNumber( 10 ); // (not needed for direct weaving)
    ab = dataBuffers.bbuf1;
    aboffset = 0;

    int selev = 0;
    for( int n=0; n<size; n++ ) // loop over nodes
    { 
      OsmNode node = nodes[n];
      int ilon = node.ilon;
      int ilat = node.ilat;
    
      // future escapes (turn restrictions?)
      short trExceptions = 0;
      for(;;)
      {
        int featureId = bc.decodeVarBits();
        if ( featureId == 0 ) break;
        int bitsize = bc.decodeNoisyNumber( 5 );

        if ( featureId == 2 ) // exceptions to turn-restriction
        {
          trExceptions = (short)bc.decodeBounded( 1023 );
        }
        else if ( featureId == 1 ) // turn-restriction
        {
          TurnRestriction tr = new TurnRestriction();
          tr.exceptions =  trExceptions;
          trExceptions = 0;
          tr.isPositive =  bc.decodeBit();
          tr.fromLon = ilon + bc.decodeNoisyDiff( 10 );
          tr.fromLat = ilat + bc.decodeNoisyDiff( 10 );
          tr.toLon = ilon + bc.decodeNoisyDiff( 10 );
          tr.toLat = ilat + bc.decodeNoisyDiff( 10 );
          node.addTurnRestriction( tr );
        }
        else
        {
          for( int i=0; i< bitsize; i++ ) bc.decodeBit(); // unknown feature, just skip
        }
      }

      selev += nodeEleDiff.decodeSignedValue();
      node.selev = (short)selev;
      TagValueWrapper nodeTags = nodeTagCoder.decodeTagValueSet();
      node.nodeDescription = nodeTags == null ? null : nodeTags.data; // TODO: unified?

      int links = bc.decodeNoisyNumber( 1 );
      if ( debug ) System.out.println( "***   decoding node " + ilon + "/" + ilat + " with links=" + links );
      for( int li=0; li<links; li++ )
      {
        int nodeIdx = n + nodeIdxDiff.decodeSignedValue();
        
        int dlon_remaining;
        int dlat_remaining;

        boolean isReverse = false;
        if ( nodeIdx != n ) // internal (forward-) link
        {
          dlon_remaining = nodes[nodeIdx].ilon - ilon;
          dlat_remaining = nodes[nodeIdx].ilat - ilat;
        }
        else
        {
          isReverse = bc.decodeBit();
          dlon_remaining = extLonDiff.decodeSignedValue();
          dlat_remaining = extLatDiff.decodeSignedValue();
        }
        if ( debug ) System.out.println( "***     decoding link to " + (ilon+dlon_remaining) + "/" + (ilat+dlat_remaining) + " extern=" + (nodeIdx == n) );

        TagValueWrapper wayTags = wayTagCoder.decodeTagValueSet();

        int linklon = ilon + dlon_remaining;
        int linklat = ilat + dlat_remaining;
        aboffset = 0;
        if ( !isReverse ) // write geometry for forward links only
        {
          WaypointMatcher matcher = wayTags == null || wayTags.accessType < 2 ? null : waypointMatcher;
          int ilontarget = ilon + dlon_remaining;
          int ilattarget = ilat + dlat_remaining;
          if ( matcher != null )
          {
            if ( !matcher.start( ilon, ilat, ilontarget, ilattarget ) )
            {
              matcher = null;
            }
          }
          
          int transcount = bc.decodeVarBits();
          if ( debug ) System.out.println( "***       decoding geometry with count=" + transcount );
          int count = transcount+1;
          for( int i=0; i<transcount; i++ )
          {
            int dlon = bc.decodePredictedValue( dlon_remaining/count );
            int dlat = bc.decodePredictedValue( dlat_remaining/count );
            dlon_remaining -= dlon;
            dlat_remaining -= dlat;
            count--;
            int elediff = transEleDiff.decodeSignedValue();
            if ( wayTags != null )
            {
              writeVarLengthSigned( dlon );
              writeVarLengthSigned( dlat );
              writeVarLengthSigned( elediff );
            }
            
            if ( matcher != null ) matcher.transferNode( ilontarget - dlon_remaining, ilattarget - dlat_remaining );
          }
          if ( matcher != null ) matcher.end();
        }

        if ( wayTags != null )
        {
          byte[] geometry = null;
          if ( aboffset > 0 )
          {
            geometry = new byte[aboffset];
            System.arraycopy( ab, 0, geometry, 0, aboffset );
          }
        
          if ( nodeIdx != n ) // valid internal (forward-) link
          {
            OsmNode node2 = nodes[nodeIdx];
            OsmLink link = node.isLinkUnused() ? node : ( node2.isLinkUnused() ? node2 : null );
            if ( link == null )
            {
              link = new OsmLink();
            }
            link.descriptionBitmap = wayTags.data;
            link.geometry = geometry;
            node.addLink( link, isReverse, node2 );
          }
          else // weave external link
          {
            node.addLink( linklon, linklat, wayTags.data, geometry, hollowNodes, isReverse );
            node.visitID = 1;
          }
        }
      } // ... loop over links
    } // ... loop over nodes

    hollowNodes.cleanupAndCount( nodes );
  }

  public long expandId( int id32 )
  {
    int dlon = 0;
    int dlat = 0;

    for( int bm = 1; bm < 0x8000; bm <<= 1 )
    {
      if ( (id32 & 1) != 0 ) dlon |= bm;
      if ( (id32 & 2) != 0 ) dlat |= bm;
      id32 >>= 2;
    }

    int lon32 = lonBase + dlon;
    int lat32 = latBase + dlat;

    return ((long)lon32)<<32 | lat32;
  }

}