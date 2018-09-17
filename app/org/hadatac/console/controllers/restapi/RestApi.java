package org.hadatac.console.controllers.restapi;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Iterator;

import org.hadatac.utils.ApiUtil;
import org.hadatac.utils.NameSpaces;
import org.hadatac.entity.pojo.Study;
import org.hadatac.entity.pojo.StudyObject;
import org.hadatac.entity.pojo.Measurement;
import org.hadatac.entity.pojo.ObjectCollection;
import org.hadatac.entity.pojo.DataAcquisitionSchema;
import org.hadatac.entity.pojo.DataAcquisitionSchemaAttribute;
import org.hadatac.entity.pojo.ObjectAccessSpec;
import org.hadatac.utils.State;
import org.hadatac.utils.CollectionUtil;
import org.hadatac.console.models.Pivot;
import org.hadatac.console.http.SPARQLUtils;
import org.hadatac.console.http.SolrUtils;

import be.objectify.deadbolt.java.actions.Group;
import be.objectify.deadbolt.java.actions.Restrict;
import be.objectify.deadbolt.java.actions.SubjectPresent;
import org.hadatac.console.controllers.AuthApplication;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFactory;
import org.apache.jena.query.ResultSetRewindable;

import com.typesafe.config.ConfigFactory;
import play.mvc.Result;
import play.mvc.Controller;
import play.libs.Json;

public class RestApi extends Controller {

    private final static int OBJ = 0;
    private final static int DPL = 1;

//****************
// UTILITY METHODS
//****************
    // Utility methods for formatting the "values ?class { <stuff> }" part of the blazegraph queries
    private String formatQueryValues(List<String> uris){
        String classes = "";
        for (String s : uris) {
            classes += "<" + s + "> ";
        }
        return classes;
    }
    private String formatQueryValues(String uri){
        if(uri == null) return null;
        else return "<" + uri + "> ";
    }
    
    // Utilty method for parsing blazegraph query results
    private List<String> parsePivotForVars (Pivot p) {
        List<String> results = new ArrayList<String>();
        String str = "";
        for (Pivot pivot_ent : p.children) {
            str = pivot_ent.getValue();
            results.add(str);
        }
        //System.out.println("[getVars parsePivotForVars] results: \n" + results);
        return results;
    }// /parsePivotForVars()


//*******************
// SOLR QUERY METHODS
//*******************
    // query solr to see for what variables we have measurements
    // and return the URI's for those variables
    private List<String> getUsedVarsInStudy(String studyUri){
        SolrQuery solrQuery = new SolrQuery();
        // restrict to the study provided by parameter
        solrQuery.setQuery("study_uri_str:\"" + studyUri + "\"");
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.setFacetLimit(-1);
        solrQuery.setParam("json.facet", "{ "
                + "vars:{ "
                + "type: terms, "
                + "field: characteristic_uri_str, "
            // limit is important - the default is only 10 facets (variables) if this is left out
                + "limit: 1000}}");
        // parse results
        List<String> uris = new ArrayList<String>();
        try {
            SolrClient solr = new HttpSolrClient.Builder(
                    CollectionUtil.getCollectionPath(CollectionUtil.Collection.DATA_ACQUISITION)).build();
            QueryResponse queryResponse = solr.query(solrQuery, SolrRequest.METHOD.POST);
            solr.close();
            Pivot pivot = Pivot.parseQueryResponse(queryResponse);
            uris = parsePivotForVars(pivot);
        } catch (Exception e) {
            System.out.println("[ERROR] RestApi.getUsedVarsInStudy() - Exception message: " + e.getMessage());
            return null;
        }
        System.out.println("[getUsedVarsInStudy] Found " + uris.size() + " variables for this study");
        return uris;
    }// /getUsedVarsInStudy()

    // as above, we're going to restrict to all variables used in measurements
    // Limit here is increased to 5000
    private List<String> getAllUsedVars(){
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery("*:*");
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.setFacetLimit(-1);
        solrQuery.setParam("json.facet", "{ "
                + "vars:{ "
                + "type: terms, "
                + "field: characteristic_uri_str, "
            // limit is important - the default is only 10 facets (variables) if this is left out
                + "limit: 5000}}");
        //System.out.println("[getAllUsedVars] solrQuery = " + solrQuery);
        // parse results
        List<String> uris = new ArrayList<String>();
        try {
            SolrClient solr = new HttpSolrClient.Builder(
                    CollectionUtil.getCollectionPath(CollectionUtil.Collection.DATA_ACQUISITION)).build();
            QueryResponse queryResponse = solr.query(solrQuery, SolrRequest.METHOD.POST);
            //System.out.println("[getAllUsedVars] res: " + queryResponse);
            solr.close();
            Pivot pivot = Pivot.parseQueryResponse(queryResponse);
            uris = parsePivotForVars(pivot);
        } catch (Exception e) {
            System.out.println("[ERROR] RestApi.getAllUsedVars() - Exception message: " + e.getMessage());
            return null;
        }
        System.out.println("[getAllUsedVars] Found " + uris.size() + " variables with measurements");
        return uris;
    }// getAllUsedVars

//"study_uri_str":"http://hadatac.org/kb/hbgd#STD-CPP"
//"dasa_uri_str":"http://hadatac.org/kb/hbgd#DASA-HBGD-SUBJ-APGAR1"
// need to get the actual variable URI....
    // getting measurements from solr!
    private SolrDocumentList getSolrMeasurements(String studyUri, String variableUri){
        //List<Measurement> listMeasurement = new ArrayList<Measurement>();
        SolrDocumentList results = null;
        // build Solr query!
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery("study_uri_str:\"" + studyUri + "\"" + 
                           "AND characteristic_uri_str:\"" + variableUri + "\"");
        solrQuery.setRows(10000000);
        // make Solr query!
        try {
            SolrClient solr = new HttpSolrClient.Builder(
                    CollectionUtil.getCollectionPath(CollectionUtil.Collection.DATA_ACQUISITION)).build();
            QueryResponse queryResponse = solr.query(solrQuery, SolrRequest.METHOD.POST);
            //System.out.println("[getSolrMeasurements] res: " + queryResponse);
            solr.close();

            results = queryResponse.getResults();
        } catch (Exception e) {
            System.out.println("[RestAPI.getMeasurements] ERROR: " + e.getMessage());
        }
        return results;
    }// /getSolrMeasurements()

    // getting measurements from solr for a particular study object
    private SolrDocumentList getSolrMeasurements(int obj_dpl, String studyUri, String variableUri, String objUri){
        SolrDocumentList results = null;
        // build Solr query!
        SolrQuery solrQuery = new SolrQuery();
	if (obj_dpl == OBJ) {
	    solrQuery.setQuery("study_uri_str:\"" + studyUri + "\"" +
			       "AND study_object_uri_str:\"" + objUri + "\"" +
			       "AND characteristic_uri_str:\"" + variableUri + "\"");
	} else {
	    SolrDocumentList oasResult = getSolrOASs(objUri);
	    if (oasResult == null) {
		return null;
	    } 
	    String oasStr = parseOASs(oasResult);
	    if (oasStr == null || oasStr.equals("")) {
		return null;
	    }
	    solrQuery.setQuery("study_uri_str:\"" + studyUri + "\"" +
			       "AND " +  oasStr + " " + 
			       "AND characteristic_uri_str:\"" + variableUri + "\"");
	}
        solrQuery.setRows(10000);
        System.out.println("[RestAPI] solr query: " + solrQuery);
        // make Solr query!
        try {
            SolrClient solr = new HttpSolrClient.Builder(
                    CollectionUtil.getCollectionPath(CollectionUtil.Collection.DATA_ACQUISITION)).build();
            QueryResponse queryResponse = solr.query(solrQuery, SolrRequest.METHOD.POST);
            //System.out.println("[getSolrMeasurements] res: " + queryResponse);
            solr.close();

            results = queryResponse.getResults();
        } catch (Exception e) {
            System.out.println("[RestAPI.getMeasurements] ERROR: " + e.getMessage());
        }
        return results;
    }// /getSolrMeasurements()

    // getting measurements from solr for a particular study object
    private SolrDocumentList getSolrMeasurements(int obj_dpl, String studyUri, String variableUri, String objUri, String fromdatetime, String todatetime){
        //List<Measurement> listMeasurement = new ArrayList<Measurement>();
        SolrDocumentList results = null;
        // build Solr query!
        SolrQuery solrQuery = new SolrQuery();
	if (obj_dpl == OBJ) {
	    solrQuery.setQuery("study_uri_str:\"" + studyUri + "\"" +
			       "AND study_object_uri_str:\"" + objUri + "\"" +
			       "AND characteristic_uri_str:\"" + variableUri + "\"" + 
			       "AND timestamp_date:[" + fromdatetime + " TO " + todatetime + "]");
	} else {
	    SolrDocumentList oasResult = getSolrOASs(objUri);
	    if (oasResult == null) {
		return null;
	    } 
	    String oasStr = parseOASs(oasResult);
	    if (oasStr == null || oasStr.equals("")) {
		return null;
	    }
	    solrQuery.setQuery("study_uri_str:\"" + studyUri + "\"" +
			       "AND " + oasStr  + " " + 
			       "AND characteristic_uri_str:\"" + variableUri + "\"" + 
			       "AND timestamp_date:[" + fromdatetime + " TO " + todatetime + "]");
	}
        solrQuery.setRows(10000);
        System.out.println("[RestAPI] solr query: " + solrQuery);
        // make Solr query!
        try {
            SolrClient solr = new HttpSolrClient.Builder(
                    CollectionUtil.getCollectionPath(CollectionUtil.Collection.DATA_ACQUISITION)).build();
            QueryResponse queryResponse = solr.query(solrQuery, SolrRequest.METHOD.POST);
            //System.out.println("[getSolrMeasurements] res: " + queryResponse);
            solr.close();

            results = queryResponse.getResults();
        } catch (Exception e) {
            System.out.println("[RestAPI.getMeasurements] ERROR: " + e.getMessage());
        }
        return results;
    }// /getSolrMeasurements()

    private String getSolrMeasurementsTimeRange(int obj_dpl, String studyUri, String variableUri, String objUri){
	String firstTime = null;
	String lastTime = null;
        SolrDocumentList results = null;
        // build first Solr query!
        SolrQuery solrQuery = new SolrQuery();
	if (obj_dpl == OBJ) {
	    solrQuery.setQuery("study_uri_str:\"" + studyUri + "\"" +
			       "AND study_object_uri_str:\"" + objUri + "\"" +
			       "AND characteristic_uri_str:\"" + variableUri + "\"" );
	} else {
	    SolrDocumentList oasResult = getSolrOASs(objUri);
	    if (oasResult == null) {
		return null;
	    } 
	    String oasStr = parseOASs(oasResult);
	    if (oasStr == null || oasStr.equals("")) {
		return null;
	    }
	    solrQuery.setQuery("study_uri_str:\"" + studyUri + "\"" +
			       "AND " +  oasStr + " " + 
			       "AND characteristic_uri_str:\"" + variableUri + "\"");
	}
        solrQuery.setRows(1);
	solrQuery.setSort("timestamp_date",SolrQuery.ORDER.asc);
        System.out.println("[RestAPI] solr query: " + solrQuery);
	SolrClient solr = null;
        QueryResponse queryResponse = null;
        // make Solr queries!
        try {
            solr = new HttpSolrClient.Builder(
                    CollectionUtil.getCollectionPath(CollectionUtil.Collection.DATA_ACQUISITION)).build();
            queryResponse = solr.query(solrQuery, SolrRequest.METHOD.POST);
            //System.out.println("[getSolrMeasurements] res: " + queryResponse);
            solr.close();

            results = queryResponse.getResults();
        } catch (Exception e) {
            System.out.println("[RestAPI.getMeasurements] ERROR: " + e.getMessage());
	    return "ERROR: no initial time for selection";
        }
        firstTime = parseMeasurementTimeStamp(results);
	solrQuery.setSort("timestamp_date",SolrQuery.ORDER.desc);
        try {
            solr = new HttpSolrClient.Builder(
                    CollectionUtil.getCollectionPath(CollectionUtil.Collection.DATA_ACQUISITION)).build();
            queryResponse = solr.query(solrQuery, SolrRequest.METHOD.POST);
            //System.out.println("[getSolrMeasurements] res: " + queryResponse);
            solr.close();

            results = queryResponse.getResults();
        } catch (Exception e) {
            System.out.println("[RestAPI.getMeasurements] ERROR: " + e.getMessage());
	    return "ERROR: no final time for selection";
        }
        lastTime = parseMeasurementTimeStamp(results);
	if (firstTime != null && !firstTime.equals("") && lastTime != null && !lastTime.equals("")) {
	    return "[" + firstTime + ";" + lastTime + "]";
	}
	return "ERROR";
    }// /getSolrMeasurementsTimeRange()

    // getting OASs from solr!
    private SolrDocumentList getSolrOASs(String dplUri){
        SolrDocumentList results = null;
        // build Solr query!
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery("deployment_uri_str:\"" + dplUri + "\"");
        solrQuery.setRows(10000);
        // make Solr query!
        try {
            SolrClient solr = new HttpSolrClient.Builder(
                    CollectionUtil.getCollectionPath(CollectionUtil.Collection.DATA_COLLECTION)).build();
            QueryResponse queryResponse = solr.query(solrQuery, SolrRequest.METHOD.POST);
            solr.close();

            results = queryResponse.getResults();
        } catch (Exception e) {
            System.out.println("[RestAPI.getSolrOASs] ERROR: " + e.getMessage());
        }
        return results;
    }// /getSolrOASs()

//*************************
// BLAZEGRAPH QUERY METHODS
//*************************
    // given one or more variable attribute URI's, query blazegraph for 
    // the details on those variables and construct JSON array from results
    private ArrayNode variableQuery(String classes){
        if(classes == null){
            return null; 
        }
        String sparqlQueryString = NameSpaces.getInstance().printSparqlNameSpaceList() + 
                "select distinct ?class ?label ?dasa ?unit ?shortname ?unitlabel ?itype ?itypelabel where {" + 
                "?class rdfs:label ?label ." +
                "?dasa hasco:hasAttribute ?class ." + 
                "?dasa hasco:hasUnit ?unit . " + 
                "OPTIONAL { ?dasa rdfs:label ?shortname .} " + 
                "OPTIONAL { ?unit rdfs:label ?unitlabel . } " +
                "OPTIONAL { ?class rdfs:subClassOf+ ?itype . " +
                "           ?itype rdfs:subClassOf hasco:StudyIndicator . " +
                "           ?itype rdfs:label ?itypelabel . }" +
                "} values ?class { " + classes + "} ";
        
        System.out.println("[VariableQuery] sparql query\n" + sparqlQueryString);
		
		ResultSetRewindable resultsrw = SPARQLUtils.select(
                CollectionUtil.getCollectionPath(CollectionUtil.Collection.METADATA_SPARQL), sparqlQueryString);

        if (resultsrw.size() == 0) {
            System.out.println("[VariableQuery] No variables found in blazegraph!");
            return null;
        }
        
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode anode = mapper.createArrayNode();
        while(resultsrw.hasNext()){
            ObjectNode temp = mapper.createObjectNode();
			QuerySolution soln = resultsrw.next();
			if (soln.get("class") != null) {
				temp.put("uri", soln.get("class").toString());
			} else {
                System.out.println("[VariableQuery] ERROR: Result returned without URI? Skipping....");
                continue;
            }
			if (soln.get("label") != null) {
				temp.put("label", soln.get("label").toString());
			}
			if (soln.get("dasa") != null) {
				temp.put("dasa", soln.get("dasa").toString());
			}
			if (soln.get("unit") != null) {
				temp.put("unit", soln.get("unit").toString());
			}
			if (soln.get("unitlabel") != null) {
				temp.put("unitlabel", soln.get("unitlabel").toString());
			}
			if (soln.get("shortname") != null) {
				temp.put("shortname", soln.get("shortname").toString());
			}
			if (soln.get("itype") != null) {
				temp.put("indicatortype", soln.get("itype").toString());
			}
			if (soln.get("itypelabel") != null) {
				temp.put("indicatortypelabel", soln.get("itypelabel").toString());
			}
            anode.add(temp);
        }// /parse sparql results
        System.out.println("[VariableQuery] parsed " + anode.size() + " results into array");
        return anode;        
    }// /variableQuery()

    // Given one or more variables, gets details on the indicator types for each of those variables
    private ArrayNode indicatorQuery(String classes){
        if(classes == null){
            return null; 
        }
        String sparqlQueryString = NameSpaces.getInstance().printSparqlNameSpaceList() + 
                "select distinct ?indicator ?label where {" + 
                "?class rdfs:subClassOf+ ?indicator . " + 
                "?indicator rdfs:subClassOf hasco:StudyIndicator ." +
                "?indicator rdfs:label ?label ." + 
                "} values ?class { " + classes + "} ";
        System.out.println("[VariableQuery] sparql query\n" + sparqlQueryString);
		
		ResultSetRewindable resultsrw = SPARQLUtils.select(
                CollectionUtil.getCollectionPath(CollectionUtil.Collection.METADATA_SPARQL), sparqlQueryString);

        if(resultsrw.size() == 0){
            System.out.println("[IndicatorQuery] No indicators found in blazegraph!");
            return null;
        }
        
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode anode = mapper.createArrayNode();
        while(resultsrw.hasNext()){
            ObjectNode temp = mapper.createObjectNode();
			QuerySolution soln = resultsrw.next();
			if (soln.get("indicator") != null) {
				temp.put("uri", soln.get("indicator").toString());
			} else {
                System.out.println("[IndicatorQuery] ERROR: Result returned without URI? Skipping....");
                continue;
            }
			if (soln.get("label") != null) {
				temp.put("label", soln.get("label").toString());
			}
			if (soln.get("class") != null) {
				temp.put("varclass", soln.get("class").toString());
			}
            anode.add(temp);
        }// /parse sparql results
        System.out.println("[IndicatorQuery] parsed " + anode.size() + " results into array");
        return anode;
    }// /indicatorquery

    // handles blazegraph query and result parsing for unit details
    private ArrayNode unitsQuery(String classes){
        if(classes == null){
            return null; 
        }
        String sparqlQueryString = NameSpaces.getInstance().printSparqlNameSpaceList() + 
                "select distinct ?uri ?label ?short where {" + 
                "?dasa hasco:hasAttribute ?class ." + 
                "?dasa hasco:hasUnit ?uri . " + 
                "?uri rdfs:label ?label ." +
                "OPTIONAL { ?uri obo:hasExactSynonym ?short . }" +
                "} values ?class { " + classes + "} ";

        //System.out.println("[unitsQuery] sparql query\n" + sparqlQueryString);
		
		ResultSetRewindable resultsrw = SPARQLUtils.select(
                CollectionUtil.getCollectionPath(CollectionUtil.Collection.METADATA_SPARQL), sparqlQueryString);

        if (resultsrw.size() == 0) {
            System.out.println("[unitsQuery] No units found in blazegraph!");
            return null;
        }
        
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode anode = mapper.createArrayNode();

        while(resultsrw.hasNext()){
            ObjectNode temp = mapper.createObjectNode();
			QuerySolution soln = resultsrw.next();
			if (soln.get("uri") != null) {
				temp.put("uri", soln.get("uri").toString());
			} else {
                System.out.println("[unitsQuery] ERROR: Result returned without URI? Skipping....");
                continue;
            }
			if (soln.get("label") != null) {
				temp.put("label", soln.get("label").toString());
			}
			if (soln.get("short") != null) {
				temp.put("shortlabel", soln.get("short").toString());
			}
            anode.add(temp);
        }// /parse sparql results
        System.out.println("[unitsQuery] parsed " + anode.size() + " results into array");
        return anode;
    }// /unitsQuery

    // handles blazegraph query and result parsing for deployment details
    private ArrayNode deploymentsQuery(){
        String sparqlQueryString = NameSpaces.getInstance().printSparqlNameSpaceList() + 
	    "select ?dpl ?plt ?ins where { " + 
	    "?dpl a vstoi:Deployment . " + 
	    "?dpl vstoi:hasPlatform ?plt . " + 
	    "?dpl hasco:hasInstrument ?ins . " +
	    "}";
        //System.out.println("[deploymentsQuery] sparql query\n" + sparqlQueryString);
	
	ResultSetRewindable resultsrw = SPARQLUtils.select(
		  CollectionUtil.getCollectionPath(CollectionUtil.Collection.METADATA_SPARQL), sparqlQueryString);

        if (resultsrw.size() == 0) {
            System.out.println("[deploymentsQuery] No deployments found in blazegraph!");
            return null;
        }
        
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode anode = mapper.createArrayNode();

        while(resultsrw.hasNext()){
            ObjectNode temp = mapper.createObjectNode();
	    QuerySolution soln = resultsrw.next();
	    if (soln.get("dpl") != null) {
		temp.put("uri", soln.get("dpl").toString());
	    } else {
                System.out.println("[deploymentsQuery] ERROR: Result returned without URI? Skipping....");
                continue;
            }
	    if (soln.get("plt") != null) {
		temp.put("platform", soln.get("plt").toString());
	    }
	    if (soln.get("ins") != null) {
		temp.put("instrument", soln.get("ins").toString());
	    }
            anode.add(temp);
        }// /parse sparql results
        System.out.println("[deploymentsQuery] parsed " + anode.size() + " results into array");
        return anode;
    }// /deploymentsQuery
    
    private ArrayNode ocQuery(String ocUri){
        if(ocUri == null){
            return null; 
        }
        String sparqlQueryString = NameSpaces.getInstance().printSparqlNameSpaceList() + 
                "SELECT ?objUri ?typeUri ?studyUri WHERE { " + 
                "   ?objUri hasco:isMemberOf  <" + ocUri + "> . " +
                "   ?objUri a ?typeUri . " +
                "   <" + ocUri + "> hasco:isMemberOf  ?studyUri . " +
                " } ";

        System.out.println("[ocQuery] sparql query\n" + sparqlQueryString);
		
		ResultSetRewindable resultsrw = SPARQLUtils.select(
                CollectionUtil.getCollectionPath(CollectionUtil.Collection.METADATA_SPARQL), sparqlQueryString);

        if (resultsrw.size() == 0) {
            System.out.println("[ocQuery] No objects found in collection " + ocUri);
            return null;
        }
        
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode anode = mapper.createArrayNode();

        while(resultsrw.hasNext()){
            ObjectNode temp = mapper.createObjectNode();
			QuerySolution soln = resultsrw.next();
			if (soln.get("objUri") != null) {
				temp.put("objectUri", soln.get("objUri").toString());
			} else {
                System.out.println("[ocQuery] ERROR: Result returned without URI? Skipping....");
                continue;
            }
			if (soln.get("typeUri") != null) {
				temp.put("typeUri", soln.get("typeUri").toString());
			}
            if (soln.get("studyUri") != null) {
				temp.put("studyUri", soln.get("studyUri").toString());
			}
            anode.add(temp);
        }// /parse sparql results
        System.out.println("[ocUri] parsed " + anode.size() + " objects into array");
        return anode;
    }// /getAllOCs

    private ObjectAccessSpec getOAS(String oasUri) {
	if (oasUri == null || oasUri.equals("")) {
	    return null;
	}
	ObjectAccessSpec oas = ObjectAccessSpec.findByUri(oasUri);
	return oas;
    }// /getOAS

// ********
// GET ALL:
// ********

    // ********
    // Studies!
    // ********
    //@Restrict(@Group(AuthApplication.DATA_OWNER_ROLE))
    public Result getAllStudies(){
        ObjectMapper mapper = new ObjectMapper();
        List<Study> theStudies = Study.find();
        System.out.println("[getAllStudies] found " + theStudies.size() + " things");
        if(theStudies.size() == 0){
            return notFound(ApiUtil.createResponse("No studies found", false));
        }
        ArrayNode theResults = mapper.createArrayNode();
        for(Study st : theStudies){
            ObjectNode temp = mapper.createObjectNode();
            List<String> vars = getUsedVarsInStudy(st.getUri());
            ArrayNode anode = mapper.convertValue(vars, ArrayNode.class);
            //ArrayNode anode = variableQuery(formatQueryValues(uris));
            if(anode == null){
                System.out.println("[RestApi] WARN: no variables found for study " + st.getUri());
            }
            temp.set("study_info", mapper.convertValue(theStudies, JsonNode.class));
            temp.set("variable_uris", anode);
            theResults.add(temp);
        }
        JsonNode jsonObject = mapper.convertValue(theResults, JsonNode.class);
        return ok(ApiUtil.createResponse(jsonObject, true));
    }// /getAllStudies()

    // **********
    // Variables!
    // **********
    // This is the same as the study-specific method,
    // but gets all of the variables for which we have
    // measurements in hadatac
    public Result getAllVariables(){
        ObjectMapper mapper = new ObjectMapper();
        // 1. Query Solr for all variables for which we have measurements
        List<String> uris = getAllUsedVars();
        if(uris.size() == 0) return notFound(ApiUtil.createResponse("Encountered SOLR error!", false));
        // 2. Query Blazegraph for those details
        ArrayNode anode = variableQuery(formatQueryValues(uris));
        // 3. Construct response
        if (anode == null){
            return notFound(ApiUtil.createResponse("Encountered Blazegraph error!", false));
        } else{
            JsonNode jsonObject = mapper.convertValue(anode, JsonNode.class);
            System.out.println("[getAllVars] done");
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getAllVariables

    // ******
    // Units!
    // ******
    // Mirrors getAllVariables, but returns Unit details instead of Variable details
    public Result getAllUnits(){
        ObjectMapper mapper = new ObjectMapper();
        // 1. Query Solr for all variables for which we have measurements
        List<String> uris = getAllUsedVars();
        if(uris.size() == 0) return notFound(ApiUtil.createResponse("Encountered SOLR error!", false));
        // 2. Query Blazegraph for details on those units
        ArrayNode anode = unitsQuery(formatQueryValues(uris));
        // 3. Construct response
        if (anode == null){
            return notFound(ApiUtil.createResponse("Encountered Blazegraph error!", false));
        } else{
            JsonNode jsonObject = mapper.convertValue(anode, JsonNode.class);
            System.out.println("[getAllUnits] Done");
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getAllUnits

    // ************
    // Deployments!
    // ************
    public Result getAllDeployments(){
        ObjectMapper mapper = new ObjectMapper();
        // 1. Query Blazegraph for deployments
        ArrayNode anode = deploymentsQuery();
        // 2. Construct response
        if (anode == null){
            return notFound(ApiUtil.createResponse("Encountered Blazegraph error!", false));
        } else{
            JsonNode jsonObject = mapper.convertValue(anode, JsonNode.class);
            System.out.println("[getAllDeployments] Done");
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getAllDeployments

    // ***********
    // Indicators!
    // ***********
    // Mirrors getAllVariables, but returns Indicator details instead of Variable details
    public Result getAllIndicators(){
        ObjectMapper mapper = new ObjectMapper();
        // 1. Query Solr for all variables for which we have measurements
        List<String> uris = getAllUsedVars();
        if(uris.size() == 0) return notFound(ApiUtil.createResponse("Encountered SOLR error!", false));
        // 2. Query Blazegraph for details on those units
        ArrayNode anode = indicatorQuery(formatQueryValues(uris));
        // 3. Construct response
        if (anode == null){
            return notFound(ApiUtil.createResponse("Encountered Blazegraph error!", false));
        } else{
            JsonNode jsonObject = mapper.convertValue(anode, JsonNode.class);
            System.out.println("[getAllIndicators] Done");
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getAllUnits



// ********
// GET FOR GIVEN STUDY:
// ********

    // ******
    // Study!
    // ******
    //@Restrict(@Group(AuthApplication.DATA_OWNER_ROLE))
    // test with http%3A%2F%2Fhadatac.org%2Fkb%2Fhbgd%23STD-CPP4
    public Result getStudy(String studyUri){
        ObjectMapper mapper = new ObjectMapper();
        Study result = Study.find(studyUri);
        System.out.println("[RestAPI] type: " + result.getType());
        if(result == null || result.getType() == null || result.getType() == ""){
            return notFound(ApiUtil.createResponse("Study with name/ID " + studyUri + " not found", false));
        } 
        // get the list of variables in that study
        // serialize the Study object first as ObjectNode
        //   as JsonNode is immutable and meant to be read-only
        ObjectNode obj = mapper.convertValue(result, ObjectNode.class);
        List<String> vars = getUsedVarsInStudy(studyUri);
        ArrayNode anode = mapper.convertValue(vars, ArrayNode.class);
        //ArrayNode anode = variableQuery(formatQueryValues(uris));
        if(anode == null){
            System.out.println("[RestApi] WARN: no variables found for study " + studyUri);
        }
        obj.set("variable_uris", anode);
        JsonNode jsonObject = mapper.convertValue(obj, JsonNode.class);
        return ok(ApiUtil.createResponse(jsonObject, true));
    }// /getStudy()

    // **********
    // Variables!
    // **********
    // test with http%3A%2F%2Fhadatac.org%2Fkb%2Fhbgd%23STD-CPP4
    // The DASchema will return ALL variables in the schema for the given study
    // We need to hit solr to make sure we only get variables for which there exist measurements
    //@Restrict(@Group(AuthApplication.DATA_OWNER_ROLE))
    public Result getVariablesInStudy(String studyUri){
        ObjectMapper mapper = new ObjectMapper();
        // 1. Query solr for all variables for which we have measurements in the given study
        List<String> uris = getUsedVarsInStudy(studyUri);
        if(uris.size() == 0) return notFound(ApiUtil.createResponse("Encountered SOLR error!", false));
        // 2. Query blazegraph for details on those variables
        ArrayNode anode = variableQuery(formatQueryValues(uris));
        // 3. Construct response
        if (anode == null){
            return notFound(ApiUtil.createResponse("Encountered Blazegraph error!", false));
        } else{
            JsonNode jsonObject = mapper.convertValue(anode, JsonNode.class);
            System.out.println("[GetVarsInStudy] done");
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getVariablesInStudy

    // ******
    // Units!
    // ******
    // This method takes the same solr query as above,
    // but instead queries the triple store for units instead of attributes
    public Result getUnitsInStudy(String studyUri){
        ObjectMapper mapper = new ObjectMapper();
        // 1. Query Solr for variables for which we have measurements for the given study
        List<String> uris = getUsedVarsInStudy(studyUri);
        if(uris == null){
            return notFound(ApiUtil.createResponse("Encountered SOLR error!", false));
        }
        // Step 2: query blazegraph for details
        ArrayNode anode = unitsQuery(formatQueryValues(uris));
        // 3. Construct response
        if (anode == null){
            return notFound(ApiUtil.createResponse("Encountered Blazegraph error!", false));
        } else{
            JsonNode jsonObject = mapper.convertValue(anode, JsonNode.class);
            System.out.println("[getUnitsInStudy] done");
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getUnitsInStudy


    // ******************
    // ObjectCollections!
    // ******************
    // Given the study URI, return the URI's and types of all object collections
    // (not the full arrays of the collections themselves)
    // Returning the ObjectCollection pojo includes an array of the included objects
    //    themselves, so to avoid overload for the extremely large OC's, we parse the
    //    query results into a smaller JSON object with URI and TYPE URI.
    public Result getOCListInStudy(String studyUri){
        ObjectMapper mapper = new ObjectMapper();
        if (studyUri == null) {
            return badRequest(ApiUtil.createResponse("No study specified", false));
        }
        String queryString = NameSpaces.getInstance().printSparqlNameSpaceList() + 
                "SELECT ?uri ?ocType WHERE { \n" + 
                "   ?ocType rdfs:subClassOf+ hasco:ObjectCollection . \n" +
                "   ?uri a ?ocType . \n" +
                "   ?uri hasco:isMemberOf <" + studyUri + "> . \n" +
                " } ";
        
        ResultSetRewindable resultsrw = SPARQLUtils.select(
                CollectionUtil.getCollectionPath(CollectionUtil.Collection.METADATA_SPARQL), queryString);
        
        ArrayNode anode = mapper.createArrayNode();

        while (resultsrw.hasNext()) {
            QuerySolution soln = resultsrw.next();
            ObjectNode temp = mapper.createObjectNode();
            if (soln.getResource("uri").getURI() != null && soln.getResource("ocType") != null) { 
    			temp.put("uri", soln.get("uri").toString());
    			temp.put("ocType", soln.get("ocType").toString());
    		} else {
                System.out.println("[GetUnitsInStudy] ERROR: Result returned without URI? Skipping....");
                continue;
            }
            anode.add(temp);
        }

        System.out.println("[getOCListInStudy] parsed " + anode.size() + " results into array");
        System.out.println("[getOCListInStudy] done");
        
        if(anode.size() < 1){
            return notFound(ApiUtil.createResponse("No object collections found for study " + studyUri, false));
        } else {
            JsonNode jsonObject = mapper.convertValue(anode, JsonNode.class);
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getOCsInStudy
 
//*************
//GET SPECIFIC: 
//*************

    // ******
    // Study!
    // ******
    // see: getStudy above

    // *********
    // Variable!
    // *********
    // test with http%3A%2F%2Fpurl.org%2Fhbgd%2Fkg%2Fns%2FSUBJID
    // or http%3A%2F%2Fpurl.org%2Fhbgd%2Fkg%2Fns%2FHAZ
    //@Restrict(@Group(AuthApplication.DATA_OWNER_ROLE))
    public Result getVariable(String attributeUri){
        ObjectMapper mapper = new ObjectMapper();
        // 2. Query blazegraph for details on the given variable
        ArrayNode anode = variableQuery(formatQueryValues(attributeUri));
        // 3. Construct response
        if (anode == null){
            return notFound(ApiUtil.createResponse("Encountered Blazegraph error!", false));
        } else{
            JsonNode jsonObject = mapper.convertValue(anode, JsonNode.class);
            System.out.println("[getVariable] done");
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getVariable()

    // *****************
    // ObjectCollection!
    // *****************
    // test with http%3A%2F%2Fhadatac.org%2Fkb%2Fhbgd%23CH-CPP4
    // Given the study URI, return the URI's of all StudyObjects that are subjects in that study
    //   - each subject / object should include a study_uri, variable_type_uris
    public Result getObjectCollection(String ocUri){
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode anode = ocQuery(ocUri);
        if(anode == null){
            return notFound(ApiUtil.createResponse("ObjectCollection with uri " + ocUri + " not found", false));
        } else {
            JsonNode jsonObject = mapper.convertValue(anode, JsonNode.class);
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getObjectCollection

    public Result getOCSize(String ocUri){
        ObjectMapper mapper = new ObjectMapper();
        ObjectCollection objs = ObjectCollection.find(ocUri);
        if(objs == null){
            return notFound(ApiUtil.createResponse("ObjectCollection with uri " + ocUri + " not found", false));
        }
        int totalResultSize = objs.getCollectionSize();
        ObjectNode res = mapper.createObjectNode();
        res.put("size", totalResultSize);
        return ok(ApiUtil.createResponse(res, true));
    }

    //total_count, page, size
    public Result getObjectsInCollection(String ocUri, int offset){
        ObjectMapper mapper = new ObjectMapper();
        int pageSize = 250;
        ObjectCollection objs = ObjectCollection.find(ocUri);
        if(objs == null){
            return notFound(ApiUtil.createResponse("ObjectCollection with uri " + ocUri + " not found", false));
        }
        int totalResultSize = objs.getCollectionSize();
        if(totalResultSize < 1){
            return notFound(ApiUtil.createResponse("ObjectCollection with uri " + ocUri + " not found", false));
        }
        if(pageSize > 250 || pageSize < 1) {
            pageSize = 250;
            System.out.println("[RestAPI] getObjectsInCollection : Yikes! Resetting that page size for you!");
        }
        List<StudyObject> results = StudyObject.findByCollectionWithPages(objs, pageSize, offset);
        if(results == null){
            return notFound(ApiUtil.createResponse("ObjectCollection with URI " + ocUri + "not found", false));
        } else {
            JsonNode jsonObject = mapper.convertValue(results, JsonNode.class);
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getObjectsInCollection

    // *************
    // Measurements!
    // *************

    private JsonNode parseMeasurements(SolrDocumentList solrResults) {

        ObjectMapper mapper = new ObjectMapper();
	Map<String, ObjectAccessSpec> oasMap = new HashMap<String, ObjectAccessSpec>();

        // parse results
        ArrayNode anode = mapper.createArrayNode();
        Iterator<SolrDocument> i = solrResults.iterator();
        while (i.hasNext()) {

            SolrDocument doc = i.next();
	    ObjectAccessSpec oas = null;
	    String oasUri = SolrUtils.getFieldValue(doc, "acquisition_uri_str");
	    if (!oasMap.containsKey(oasUri)) {
		oas = getOAS(oasUri);
		if (oas != null) {
		    oasMap.put(oasUri, oas);
		}
	    } else {
		oas = oasMap.get(oasUri);
	    }
            ObjectNode temp = mapper.createObjectNode();
            temp.put("measurementuri", SolrUtils.getFieldValue(doc, "uri"));
            temp.put("studyuri", SolrUtils.getFieldValue(doc, "study_uri_str"));
            temp.put("studyobjecturi", SolrUtils.getFieldValue(doc, "study_object_uri_str"));
            temp.put("variableuri", SolrUtils.getFieldValue(doc, "characteristic_uri_str"));
            temp.put("value", SolrUtils.getFieldValue(doc, "value_str"));
            temp.put("unituri", SolrUtils.getFieldValue(doc, "unit_uri_str"));
            temp.put("timeValue", SolrUtils.getFieldValue(doc, "time_value_double"));
            temp.put("timeUnit", SolrUtils.getFieldValue(doc, "time_value_unit_uri_str"));
            temp.put("timestamp", SolrUtils.getFieldValue(doc, "timestamp_date"));
	    if (oas == null || oas.getDeployment() == null) {
		temp.put("platform", "");
		temp.put("instrument", "");
	    } else {
		if (oas.getDeployment() != null && oas.getDeployment().getPlatform() != null) {
		    if (oas.getDeployment().getPlatform().getLabel() != null) {
			temp.put("platform", oas.getDeployment().getPlatform().getLabel());
		    } else {
			temp.put("platform", oas.getDeployment().getPlatform().getUri());
		    }
		} else {
		    temp.put("platform", "");
		}
		if (oas.getDeployment() != null && oas.getDeployment().getInstrument() != null) {
		    if (oas.getDeployment().getInstrument().getLabel() != null) {
			temp.put("instrument", oas.getDeployment().getInstrument().getLabel());
		    } else {
			temp.put("instrument", oas.getDeployment().getInstrument().getUri());
		    }
		} else {
		    temp.put("instrument", "");
		}
	    }

            anode.add(temp);
        }// /parse solr results

        if(anode == null){
            return null;
        } else {
            return mapper.convertValue(anode, JsonNode.class);
        }
    }

    private String parseMeasurementTimeStamp(SolrDocumentList solrResults) {
        Iterator<SolrDocument> i = solrResults.iterator();
        if (i.hasNext()) {
            SolrDocument doc = i.next();
            return SolrUtils.getFieldValue(doc, "timestamp_date");
        }
	return null;
    }

    private String parseOASs(SolrDocumentList solrResults) {
        Iterator<SolrDocument> i = solrResults.iterator();
	List<String> uris = new ArrayList<String>();
        while (i.hasNext()) {
            SolrDocument doc = i.next();
	    uris.add(SolrUtils.getFieldValue(doc, "uri"));
        }
	String oas = "";
	if (uris.size() == 1) {
	    oas = "acquisition_uri_str:\"" + uris.get(0) + "\"";
	    return oas;
	} 

	if (uris.size() > 0) {
	    oas = "( ";
	    for (int uri = 0; uri < uris.size(); uri++) {
		oas += "acquisition_uri_str:\"" + uris.get(uri) + "\"";
		if (uri < (uris.size() - 1)) {
		    oas += " OR ";
		}
	    }
	    oas += ")";
	    return oas;
	}

	return null;
    }

    // :study_uri/:variable_uri/
    public Result getMeasurements(String studyUri, String variableUri){
        if(variableUri == null){
            return badRequest(ApiUtil.createResponse("No variable specified", false));
        }
        if(studyUri == null){
            return badRequest(ApiUtil.createResponse("No study specified", false));
        }
        // Solr query
        SolrDocumentList solrResults = getSolrMeasurements(studyUri, variableUri);
        if(solrResults.size() < 1){
            return notFound(ApiUtil.createResponse("Solr Message: no measurements found", false));
        }

        JsonNode jsonObject = parseMeasurements(solrResults);

        if(jsonObject == null){
            return internalServerError(ApiUtil.createResponse("Error parsing measurments", false));
        } else {
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getMeasurements()

    // :study_uri/:variable_uri/:object_uri
    public Result getMeasurementsForObj(String studyUri, String variableUri, String objUri){
        if(variableUri == null){
            return badRequest(ApiUtil.createResponse("No variable specified", false));
        }
        if(studyUri == null){
            return badRequest(ApiUtil.createResponse("No study specified", false));
        }
        // Solr query
        System.out.println("[RestAPI] Getting measurements for " + objUri);
        SolrDocumentList solrResults = getSolrMeasurements(OBJ, studyUri, variableUri, objUri);
        if(solrResults.size() < 1){
            return notFound(ApiUtil.createResponse("Solr Message: no measurements found", false));
        }

        JsonNode jsonObject = parseMeasurements(solrResults);

        if(jsonObject == null){
            return internalServerError(ApiUtil.createResponse("Error parsing measurments", false));
        } else {
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getMeasurementsForObj()

    // :study_uri/:variable_uri/:object_uri/:fromdatetime/:todatetime
    public Result getMeasurementsForObjInPeriod(String studyUri, String variableUri, String objUri, String fromdatetime, String todatetime){
        if(variableUri == null){
            return badRequest(ApiUtil.createResponse("No variable specified", false));
        }
        if(studyUri == null){
            return badRequest(ApiUtil.createResponse("No study specified", false));
        }
        if(fromdatetime == null){
            return badRequest(ApiUtil.createResponse("No fromdatetime specified", false));
        }
        if(todatetime == null){
            return badRequest(ApiUtil.createResponse("No todatetime specified", false));
        }
        // Solr query
        System.out.println("[RestAPI] Getting measurements for " + objUri);
        SolrDocumentList solrResults = getSolrMeasurements(OBJ, studyUri, variableUri, objUri, fromdatetime, todatetime);
        if(solrResults.size() < 1){
            return notFound(ApiUtil.createResponse("Solr Message: no measurements found", false));
        }

        JsonNode jsonObject = parseMeasurements(solrResults);

        if(jsonObject == null){
            return internalServerError(ApiUtil.createResponse("Error parsing measurments", false));
        } else {
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getMeasurementsForObjInPeriod()

    // :study_uri/:variable_uri/:object_uri/timerange
    public Result getMeasurementsForObjTimeRange(String studyUri, String variableUri, String objUri){
        if(variableUri == null){
            return badRequest(ApiUtil.createResponse("No variable specified", false));
        }
        if(studyUri == null){
            return badRequest(ApiUtil.createResponse("No study specified", false));
        }
        // Solr query
        System.out.println("[RestAPI] Getting time range for measurements for " + objUri);
        String timerange = getSolrMeasurementsTimeRange(OBJ, studyUri, variableUri, objUri);
        if(timerange == null){
            return internalServerError(ApiUtil.createResponse("Error retrieving time range for  measurements", false));
        } else {
            return ok(timerange);
        }
    }// /getMeasurementsForObjTimeRange()

    // :study_uri/:variable_uri/:deployment_uri
    public Result getMeasurementsForDpl(String studyUri, String variableUri, String dplUri){
        if(variableUri == null){
            return badRequest(ApiUtil.createResponse("No variable specified", false));
        }
        if(studyUri == null){
            return badRequest(ApiUtil.createResponse("No study specified", false));
        }
        // Solr query
        System.out.println("[RestAPI] Getting measurements for " + dplUri);
        SolrDocumentList solrResults = getSolrMeasurements(DPL, studyUri, variableUri, dplUri);
        if(solrResults == null || solrResults.size() < 1){
            return notFound(ApiUtil.createResponse("Solr Message: no measurements found", false));
        }

        JsonNode jsonObject = parseMeasurements(solrResults);

        if(jsonObject == null){
            return internalServerError(ApiUtil.createResponse("Error parsing measurments", false));
        } else {
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getMeasurementsForDlp()

    // :study_uri/:variable_uri/:deployment_uri/:fromdatetime/:todatetime
    public Result getMeasurementsForDplInPeriod(String studyUri, String variableUri, String dplUri, String fromdatetime, String todatetime){
        if(variableUri == null){
            return badRequest(ApiUtil.createResponse("No variable specified", false));
        }
        if(studyUri == null){
            return badRequest(ApiUtil.createResponse("No study specified", false));
        }
        if(fromdatetime == null){
            return badRequest(ApiUtil.createResponse("No fromdatetime specified", false));
        }
        if(todatetime == null){
            return badRequest(ApiUtil.createResponse("No todatetime specified", false));
        }
        // Solr query
        System.out.println("[RestAPI] Getting measurements for " + dplUri);
        SolrDocumentList solrResults = getSolrMeasurements(DPL, studyUri, variableUri, dplUri, fromdatetime, todatetime);
        if(solrResults == null || solrResults.size() < 1){
            return notFound(ApiUtil.createResponse("Solr Message: no measurements found", false));
        }

        JsonNode jsonObject = parseMeasurements(solrResults);

        if(jsonObject == null){
            return internalServerError(ApiUtil.createResponse("Error parsing measurments", false));
        } else {
            return ok(ApiUtil.createResponse(jsonObject, true));
        }
    }// /getMeasurementsForDplInPeriod()

    // :study_uri/:variable_uri/:deployment_uri/timerange
    public Result getMeasurementsForDplTimeRange(String studyUri, String variableUri, String dplUri){
        if(variableUri == null){
            return badRequest(ApiUtil.createResponse("No variable specified", false));
        }
        if(studyUri == null){
            return badRequest(ApiUtil.createResponse("No study specified", false));
        }
        // Solr query
        System.out.println("[RestAPI] Getting time range for measurements for deployment " + dplUri);
        String timerange = getSolrMeasurementsTimeRange(DPL, studyUri, variableUri, dplUri);
        if(timerange == null){
            return internalServerError(ApiUtil.createResponse("Error retrieving time range for  measurements", false));
        } else {
            return ok(timerange);
        }
    }// /getMeasurementsForDplTimeRange()

}// /RestApi
