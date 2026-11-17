Action()
{
	lr_start_transaction("02_Load_Filter_Data");

	// first SR of the list, and all of them
	web_reg_save_param("srId", "LB=\"srId\":\"", "RB=\"", "Ord=1", LAST);
	web_reg_save_param("allSrIds", "LB=\"srId\":\"", "RB=\"", "Ord=ALL", LAST);

	web_custom_request("load_filter_data",
		"URL=http://localhost:18080/sr/load-filter-data",
		"Method=POST",
		"Resource=0",
		"EncType=application/json",
		"Body={\"region\":\"ALL\"}",
		LAST);

	lr_end_transaction("02_Load_Filter_Data", LR_AUTO);

	lr_think_time(1);

	lr_rendezvous("status_check");

	lr_start_transaction("03_SR_Status");

	web_reg_find("Text=\"status\"", LAST);
	web_reg_find("Text=error", "Fail=Found", LAST);

	web_url("sr_status",
		"URL=http://localhost:18080/sr/{srId}/status",
		"Resource=0",
		"RecContentType=application/json",
		"Referer=",
		"Snapshot=t3.inf",
		"Mode=HTTP",
		LAST);

	lr_end_transaction("03_SR_Status", LR_AUTO);

	lr_save_string("checked", "outcome");
	lr_output_message("SR %s checked", lr_eval_string("{srId}"));

	if (atoi(lr_eval_string("{srCount}")) > 0) {
		web_url("ping",
			"URL=http://localhost:18080/ping",
			"Resource=0",
			LAST);
	}

	return 0;
}
