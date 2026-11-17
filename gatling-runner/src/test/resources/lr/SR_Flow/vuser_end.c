vuser_end()
{
	web_revert_auto_header("Authorization");
	lr_output_message("done");
	return 0;
}
