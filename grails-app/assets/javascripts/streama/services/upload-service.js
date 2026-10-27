'use strict';

angular.module('streama').factory('uploadService', function ($http, $timeout, Upload, contextPath) {

	/**
	 * Videos are uploaded to Mux, which needs a moment to process them. Poll until the
	 * asset is ready and only then hand the file back to the caller.
	 */
	function resolveMuxUpload(data, uploadStatus, callback, errCallback) {
		if (!data || !data.muxStatus || data.muxStatus === 'ready') {
			(callback || angular.noop)(data);
			return;
		}

		uploadStatus.processing = true;
		alertify.log('Video uploaded to Mux. It will show up as soon as Mux has finished processing it.');

		var attempts = 0;
		function poll() {
			attempts++;
			if (attempts > 120) {
				uploadStatus.processing = false;
				uploadStatus.percentage = null;
				alertify.error('Mux is still processing this video. Reload the page in a few minutes to check whether it finished.');
				(errCallback || angular.noop)();
				return;
			}

			$http.get(contextPath + 'mux/fileStatus.json', {params: {id: data.id}}).then(function (response) {
				var file = response.data;

				if (file.muxStatus === 'ready') {
					uploadStatus.processing = false;
					uploadStatus.percentage = null;
					(callback || angular.noop)(file);
				} else if (file.muxStatus === 'errored') {
					uploadStatus.processing = false;
					uploadStatus.percentage = null;
					alertify.error('Mux could not process this video file.');
					(errCallback || angular.noop)(file);
				} else {
					$timeout(poll, 5000);
				}
			}, function () {
				$timeout(poll, 5000);
			});
		}

		$timeout(poll, 5000);
	}

	return {
		doUpload: function (uploadStatus, endpoint, callback, errCallback, files) {
			if (files && files.length) {
				for (var i = 0; i < files.length; i++) {
					var file = files[i];

					Upload.upload({
						url: contextPath + endpoint,
						sendObjectsAsJsonBlob: true,
						file: file
					})

						.progress(function (evt) {
							var progressPercentage = parseInt(100.0 * evt.loaded / evt.total);
							uploadStatus.percentage = progressPercentage;
						})

						.success(function (data) {
							resolveMuxUpload(data, uploadStatus, callback, errCallback);
						})
						.error(function (err) {
              console.log('%c err', 'color: deeppink; font-weight: bold; text-shadow: 0 0 5px deeppink;', arguments);
              alertify.error("File upload failed. Please close this popup and try again.", 0);
              uploadStatus.percentage = null;
              (errCallback || angular.noop)(err);
            });

				}
			}
		}
	};
});
