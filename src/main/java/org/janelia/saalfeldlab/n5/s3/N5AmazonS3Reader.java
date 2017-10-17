/**
 * Copyright (c) 2017, Stephan Saalfeld
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.janelia.saalfeldlab.n5.s3;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.janelia.saalfeldlab.n5.AbstractGsonReader;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.DefaultBlockReader;
import org.janelia.saalfeldlab.n5.GsonAttributesParser;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;

/**
 * Amazon Web Services S3-based N5 implementation.
 *
 * Amazon S3 does not have conventional files and directories, instead it operates on objects with unique keys.
 * This implementation requires that an empty attributes file is present for each group.
 * It is used for determining group existence and listing groups.
 *
 * @author Igor Pisarev
 */
public class N5AmazonS3Reader extends AbstractGsonReader {

	protected static final String jsonFile = "attributes.json";
	protected static final String delimiter = "/";

	protected final AmazonS3 s3;
	protected final String bucketName;

	/**
	 * Opens an {@link N5Reader}/{@link N5Writer} using an {@link AmazonS3} client and a given bucket name
	 * with a custom {@link GsonBuilder} to support custom attributes.
	 *
	 * If the bucket does not exist, make sure to create it by calling {@link #createContainer()}
	 * before attempting to read or write attributes, groups, or datasets, otherwise all such attempts will fail.
	 *
	 * @param s3
	 * @param bucketName
	 * @param gsonBuilder
	 */
	public N5AmazonS3Reader(final AmazonS3 s3, final String bucketName, final GsonBuilder gsonBuilder) {

		super(gsonBuilder);
		this.s3 = s3;
		this.bucketName = bucketName;
	}

	/**
	 * Opens an {@link N5Reader}/{@link N5Writer} using an {@link AmazonS3} client and a given bucket name.
	 *
	 * If the bucket does not exist, make sure to create it by calling {@link #createContainer()}
	 * before attempting to read or write attributes, groups, or datasets, otherwise all such attempts will fail.
	 *
	 * @param s3
	 * @param bucketName
	 */
	public N5AmazonS3Reader(final AmazonS3 s3, final String bucketName) {

		this(s3, bucketName, new GsonBuilder());
	}

	@Override
	public boolean exists(final String pathName) {

		final String metadataKey = getAttributesPath(pathName).toString();
		return s3.doesObjectExist(bucketName, removeFrontDelimiter(metadataKey));
	}

	@Override
	public HashMap<String, JsonElement> getAttributes(final String pathName) throws IOException {

		final String metadataKey = getAttributesPath(pathName).toString();
		if (!s3.doesObjectExist(bucketName, removeFrontDelimiter(metadataKey)))
			return new HashMap<>();

		try (final InputStream in = readS3Object(removeFrontDelimiter(metadataKey))) {
			return GsonAttributesParser.readAttributes(new InputStreamReader(in, StandardCharsets.UTF_8.name()), gson);
		}
	}

	@Override
	public DataBlock<?> readBlock(
			final String pathName,
			final DatasetAttributes datasetAttributes,
			final long[] gridPosition) throws IOException {

		final String dataBlockKey = getDataBlockPath(pathName, gridPosition).toString();
		if (!s3.doesObjectExist(bucketName, removeFrontDelimiter(dataBlockKey)))
			return null;

		try (final InputStream in = readS3Object(removeFrontDelimiter(dataBlockKey))) {
			return DefaultBlockReader.readBlock(in, datasetAttributes, gridPosition);
		}
	}

	@Override
	public String[] list(final String pathName) throws IOException {

		final String correctedPathName = removeFrontDelimiter(pathName);
		final String prefix = !correctedPathName.isEmpty() ? appendDelimiter(correctedPathName) : correctedPathName;
		final Path path = Paths.get(prefix);

		final List<String> subGroups = new ArrayList<>();
		final ListObjectsV2Request listObjectsRequest = new ListObjectsV2Request()
				.withBucketName(bucketName)
				.withPrefix(prefix)
				.withDelimiter(delimiter);
		ListObjectsV2Result objectsListing;
		do {
			objectsListing = s3.listObjectsV2(listObjectsRequest);
			for (final String commonPrefix : objectsListing.getCommonPrefixes()) {
				if (exists(commonPrefix)) {
					final Path relativePath = path.relativize(Paths.get(commonPrefix));
					subGroups.add(relativePath.toString());
				}
			}
			listObjectsRequest.setContinuationToken(objectsListing.getNextContinuationToken());
		} while (objectsListing.isTruncated());
		return subGroups.toArray(new String[subGroups.size()]);
	}

	protected InputStream readS3Object(final String objectKey) throws IOException {

		return s3.getObject(bucketName, removeFrontDelimiter(objectKey)).getObjectContent();
	}

	/**
	 * When absolute paths are passed (e.g. /group/data), AWS S3 service creates an additional root folder with an empty name.
	 * This method removes the root slash symbol and returns the corrected path.
	 *
	 * @param pathName
	 * @return
	 */
	protected static String removeFrontDelimiter(final String pathName) {

		return pathName.startsWith(delimiter) ? pathName.substring(1) : pathName;
	}

	/**
	 * When listing children objects for a group, must append a delimiter to the path (e.g. group/data/).
	 * This is necessary for not including wrong objects in the filtered set
	 * (e.g. group/data-2/attributes.json when group/data is passed without the last slash).
	 *
	 * @param pathName
	 * @return
	 */
	protected static String appendDelimiter(final String pathName) {

		return pathName.endsWith(delimiter) ? pathName : pathName + delimiter;
	}

	/**
	 * Constructs the path for a data block in a dataset at a given grid position.
	 *
	 * The returned path is
	 * <pre>
	 * $datasetPathName/$gridPosition[0]/$gridPosition[1]/.../$gridPosition[n]
	 * </pre>
	 *
	 * This is the file into which the data block will be stored.
	 *
	 * @param datasetPathName
	 * @param gridPosition
	 * @return
	 */
	protected static Path getDataBlockPath(
			final String datasetPathName,
			final long[] gridPosition) {

		final String[] pathComponents = new String[gridPosition.length];
		for (int i = 0; i < pathComponents.length; ++i)
			pathComponents[i] = Long.toString(gridPosition[i]);

		return Paths.get(datasetPathName, pathComponents);
	}

	/**
	 * Constructs the path for the attributes file of a group or dataset.
	 *
	 * @param pathName
	 * @return
	 */
	protected static Path getAttributesPath(final String pathName) {

		return Paths.get(pathName, jsonFile);
	}
}
