require 'xcodeproj'
require 'fileutils'
base='ios/App'
project=Xcodeproj::Project.open("#{base}/App.xcodeproj")
app=project.targets.find { |t| t.name=='App' }
widget=project.targets.find { |t| t.name=='BlinkWidget' }
group=project.main_group.find_subpath('BlinkLive',true)
group.path='BlinkLive'
FileUtils.mkdir_p("#{base}/BlinkLive")
%w[BlinkAttributes BlinkWidget BlinkLive].each do |name|
  FileUtils.cp("mobile/ios/#{name}.swift","#{base}/BlinkLive/#{name}.swift")
end
unless widget
  widget=project.new_target(:app_extension,'BlinkWidget',:ios,'16.2')
  widget.build_configurations.each do |c|
    c.build_settings.merge!({'PRODUCT_NAME'=>'BlinkWidget','APPLICATION_EXTENSION_API_ONLY'=>'YES','SWIFT_VERSION'=>'5.0','GENERATE_INFOPLIST_FILE'=>'NO','INFOPLIST_FILE'=>'BlinkLive/Info.plist','PRODUCT_BUNDLE_IDENTIFIER'=>'com.zhaowl.blink.countdown','MARKETING_VERSION'=>'1.0','CURRENT_PROJECT_VERSION'=>'1','TARGETED_DEVICE_FAMILY'=>'1,2','SKIP_INSTALL'=>'YES','PROVISIONING_PROFILE_SPECIFIER'=>'$(BLINK_WIDGET_PROFILE)'})
  end
  app.build_configurations.each { |c| c.build_settings['PROVISIONING_PROFILE_SPECIFIER']='$(BLINK_APP_PROFILE)' }
  app.add_dependency(widget)
  embed=app.new_copy_files_build_phase('Embed App Extensions')
  embed.dst_subfolder_spec='13'
  embed.add_file_reference(widget.product_reference).settings={'ATTRIBUTES'=>['RemoveHeadersOnCopy']}
  %w[BlinkAttributes BlinkWidget BlinkLive].each do |name|
    ref=group.new_file("#{name}.swift")
    app.source_build_phase.add_file_reference(ref) unless name=='BlinkWidget'
    widget.source_build_phase.add_file_reference(ref) unless name=='BlinkLive'
  end
end
plist=Xcodeproj::Plist.read_from_path("#{base}/App/Info.plist")
plist['NSSupportsLiveActivities']=true
Xcodeproj::Plist.write_to_path(plist,"#{base}/App/Info.plist")
Xcodeproj::Plist.write_to_path({'CFBundleIdentifier'=>'$(PRODUCT_BUNDLE_IDENTIFIER)','CFBundleName'=>'Blink 倒计时','CFBundleDisplayName'=>'Blink 倒计时','CFBundleExecutable'=>'$(EXECUTABLE_NAME)','CFBundlePackageType'=>'XPC!','CFBundleVersion'=>'$(CURRENT_PROJECT_VERSION)','CFBundleShortVersionString'=>'$(MARKETING_VERSION)','NSExtension'=>{'NSExtensionPointIdentifier'=>'com.apple.widgetkit-extension'}},"#{base}/BlinkLive/Info.plist")
scene="#{base}/App/SceneDelegate.swift"
File.write(scene,File.read(scene).gsub('= CAPBridgeViewController()','= BlinkViewController()'))
project.save
