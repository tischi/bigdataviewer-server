package bdv.server;

import bdv.img.cache.CacheHints;
import bdv.img.cache.LoadingStrategy;
import bdv.img.cache.VolatileCell;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.remote.AffineTransform3DJsonSerializer;
import bdv.img.remote.RemoteImageLoader;
import bdv.img.remote.RemoteImageLoaderMetaData;
import bdv.spimdata.SequenceDescriptionMinimal;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import com.google.gson.GsonBuilder;
import mpicbg.spim.data.SpimDataException;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.realtransform.AffineTransform3D;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.jdom2.Document;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

public class CellHandler extends ContextHandler
{
	private final VolatileGlobalCellCache< VolatileShortArray > cache;

	private final String metadataJson;

	private final RemoteImageLoaderMetaData metadata;

	private final CacheHints cacheHints;

	private final String xmlFile;

	private final String dataSetURL;

	public CellHandler( final String baseUrl, final String xmlFilename ) throws SpimDataException
	{
		final SpimDataMinimal spimData = new XmlIoSpimDataMinimal().load( xmlFilename );
		final SequenceDescriptionMinimal seq = spimData.getSequenceDescription();
		final Hdf5ImageLoader imgLoader = ( Hdf5ImageLoader ) seq.getImgLoader();
		cache = imgLoader.getCache();
		metadata = new RemoteImageLoaderMetaData( imgLoader, seq );

		final GsonBuilder gsonBuilder = new GsonBuilder();
		gsonBuilder.registerTypeAdapter( AffineTransform3D.class, new AffineTransform3DJsonSerializer() );
		gsonBuilder.enableComplexMapKeySerialization();
		metadataJson = gsonBuilder.create().toJson( metadata );
		cacheHints = new CacheHints( LoadingStrategy.BLOCKING, 0, false );

		// dataSetURL property is used for providing the XML file by replace
		// SequenceDescription>ImageLoader>baseUrl
		xmlFile = xmlFilename;
		dataSetURL = baseUrl;
	}

	@Override
	public void doHandle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException, ServletException
	{
		final String cellString = request.getParameter( "p" );

		if ( cellString == null )
		{
			provideXML( baseRequest, response );
			return;
		}

		final String[] parts = cellString.split( "/" );
		if ( parts[ 0 ].equals( "cell" ) )
		{
			final int index = Integer.parseInt( parts[ 1 ] );
			final int timepoint = Integer.parseInt( parts[ 2 ] );
			final int setup = Integer.parseInt( parts[ 3 ] );
			final int level = Integer.parseInt( parts[ 4 ] );
			VolatileCell< VolatileShortArray > cell = cache.getGlobalIfCached( timepoint, setup, level, index, cacheHints );
			if ( cell == null )
			{
				final int[] cellDims = new int[] {
						Integer.parseInt( parts[ 5 ] ),
						Integer.parseInt( parts[ 6 ] ),
						Integer.parseInt( parts[ 7 ] ) };
				final long[] cellMin = new long[] {
						Long.parseLong( parts[ 8 ] ),
						Long.parseLong( parts[ 9 ] ),
						Long.parseLong( parts[ 10 ] ) };
				cell = cache.createGlobal( cellDims, cellMin, timepoint, setup, level, index, cacheHints );
			}

			final short[] data = cell.getData().getCurrentStorageArray();
			final byte[] buf = new byte[ 2 * data.length ];
			for ( int i = 0, j = 0; i < data.length; i++ )
			{
				final short s = data[ i ];
				buf[ j++ ] = ( byte ) ( ( s >> 8 ) & 0xff );
				buf[ j++ ] = ( byte ) ( s & 0xff );
			}

			response.setContentType( "application/octet-stream" );
			response.setContentLength( buf.length );
			response.setStatus( HttpServletResponse.SC_OK );
			baseRequest.setHandled( true );
			final OutputStream os = response.getOutputStream();
			os.write( buf );
			os.close();
		}
		else if ( parts[ 0 ].equals( "init" ) )
		{
			response.setContentType( "application/octet-stream" );
			response.setStatus( HttpServletResponse.SC_OK );
			baseRequest.setHandled( true );
			final PrintWriter ow = response.getWriter();
			ow.write( metadataJson );
			ow.close();
		}
	}

	// TODO: create the remote xml file only once and keep it ready as a String
	public void provideXML( final Request baseRequest, final HttpServletResponse response ) throws IOException, ServletException
	{
		try
		{
			final XmlIoSpimDataMinimal io = new XmlIoSpimDataMinimal();
			final SpimDataMinimal spimData = io.load( xmlFile );
			final SequenceDescriptionMinimal seq = spimData.getSequenceDescription();
			seq.setImgLoader( new RemoteImageLoader( dataSetURL ) );
			final Document doc = new Document( io.toXml( spimData, spimData.getBasePath() ) );

			response.setContentType( "application/xml" );
			response.setStatus( HttpServletResponse.SC_OK );
			baseRequest.setHandled( true );
			final XMLOutputter xout = new XMLOutputter( Format.getPrettyFormat() );
			xout.output( doc, response.getOutputStream() );
//			xout.output( doc, System.out );
		}
		catch ( final Exception e )
		{
			throw new ServletException( e );
		}

//		final SAXBuilder sax = new SAXBuilder();
//		Document doc;
//		try
//		{
//			doc = sax.build( xmlFile );
//		}
//		catch ( final Exception e )
//		{
//			throw new ServletException( e );
//		}
//		final Element root = doc.getRootElement();
//		final Element SequenceDescription = root.getChild( "SequenceDescription" );
//		final Element ImageLoader = SequenceDescription.getChild( "ImageLoader" );
//
//		ImageLoader.setAttribute( "format", "bdv.remote" );
//
//		ImageLoader.removeChild( "hdf5" );
//
//		final Element baseUrl = new Element( "baseUrl" );
//		baseUrl.setText( dataSetURL );
//		ImageLoader.setContent( baseUrl );
//
//		response.setContentType( "application/xml" );
//		response.setStatus( HttpServletResponse.SC_OK );
//		baseRequest.setHandled( true );
//		final PrintWriter ow = response.getWriter();
//		ow.write( new XMLOutputter().outputString( doc ) );
//		ow.close();
	}

	public String getXmlFile()
	{
		return xmlFile;
	}
}
