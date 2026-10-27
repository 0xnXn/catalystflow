// Karma configuration file, see link for more information
// https://karma-runner.github.io/1.0/config/configuration-file.html

module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma')
    ],
    client: {
      jasmine: {
        // you can add configuration options for Jasmine here
      },
    },
    jasmineHtmlReporter: {
      suppressAll: true // removes the duplicated traces
    },
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage/front-end'),
      subdir: '.',
      reporters: [
        { type: 'html' },
        { type: 'text-summary' }
      ],
      // Baseline measured 2026-05-11 after P0 spec additions (login, new-password,
      // manager-review-form, review.services). Instrumented surface grew ~40% as
      // previously-uncovered files entered the build via new spec imports —
      // headline coverage dropped vs the original measurement but the denominator
      // is now honest. Gate set just below current measured floor so CI fails on
      // regression. Ratchet up as coverage grows.
      //   Measured: stmts 44.98, branches 30.42, funcs 41.28, lines 47
      check: {
        global: {
          statements: 44,
          branches: 30,
          functions: 41,
          lines: 46
        }
      }
    },
    reporters: ['progress', 'kjhtml'],
    browsers: ['Chrome'],
    customLaunchers: {
      ChromeHeadlessCI: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-gpu']
      }
    },
    restartOnFileChange: true
  });
};