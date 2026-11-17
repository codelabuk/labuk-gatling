vuser_init()
{
	web_set_max_html_param_len("9999");
	web_add_auto_header("Accept", "application/json");

	lr_start_transaction("01_Login");

	/* correlation: the JWT from the login response */
	web_reg_save_param_json(
		"ParamName=token",
		"QueryString=$.token",
		SEARCH_FILTERS,
		"Scope=Body",
		LAST);

	web_custom_request("login",
		"URL=http://localhost:18080/auth/login",
		"Method=POST",
		"Resource=0",
		"RecContentType=application/json",
		"Referer=",
		"Snapshot=t1.inf",
		"Mode=HTTP",
		"EncType=application/json",
		"Body={\"username\":\"{UserName}\",\"password\":\"{Password}\"}",
		LAST);

	lr_end_transaction("01_Login", LR_AUTO);

	web_add_auto_header("Authorization", "Bearer {token}");

	return 0;
}
