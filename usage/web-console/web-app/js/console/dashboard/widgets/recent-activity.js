Brooklyn.activitywidget = ( function(){
//Code for the recent activity widget.

/* INITIAL CONFIG */
    var id = "#recent-activity-table";
    var aoColumns = [   { "mDataProp": "entityDisplayName", "sTitle": "Entity Name", "sWidth":"20%" },
                        { "mDataProp": "displayName", "sTitle": "Task Name", "sWidth":"20%" },
                        { "mDataProp": "submitTimeUtc", "sTitle": "Submit time", "sWidth":"20%" },
                        { "mDataProp": "endTimeUtc", "sTitle": "End time", "sWidth":"20%" },
                        { "mDataProp": "currentStatus", "sTitle": "Status", "sWidth":"20%" }];
    
    function updateWidgetTable(json){
        Brooklyn.util.getDataTable(id, ".", aoColumns, updateLog, json, true);
    }

    function updateLog(event){
        //alert("Logs up to date");
        //could do some sort of pop up with the table data maybe?
    }


    function init(){
        getRecentActivity();
        $(Brooklyn.eventBus).bind("update", update);
    }

    function getRecentActivity(){
        $.getJSON("../entity/allActivity", updateWidgetTable).error(
            function(){$(Brooklyn.eventBus).trigger('update_failed', 'Could not obtain recent activity');}
        );
    }

    function update(){
    // if auto refresh then getRecentActivity else do nothing.
    var checked = document.getElementById("updateCheck").checked;
        if(checked){
            getRecentActivity();
            }
        }

    return {
        init: init
    };


})();
$(document).ready( Brooklyn.activitywidget.init );
