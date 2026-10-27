"""A native report must distinguish execution and actual fixture effects from arming."""
import copy
from discover_proof_report import apple_report,expected_suite
import unittest
from discover_proof_report import validate_report, expected_tests, native_exit_status

class ReportTests(unittest.TestCase):
    def positive(self):
        return dict(mode='iphone',idiom='phone',finalized=True,
            cases=[dict(name=n,status='passed') for n in expected_tests('iphone')],
            effects=[dict(scenario=n,revision=i+1,**{flag:True}) for i,(n,flag) in enumerate([
                ('rate-limit','served'),('scan-arrival','completed'),('lost-add-receipt','delivered')])],
            baseline=dict(login=True,albums=1,featureCalls=0),replay=dict(removed=True,applied=False))
    def test_positive(self):self.assertIsNone(validate_report(self.positive()).first_cause)
    def test_post_suite_interrupt_requires_a_finalized_passing_report(self):
        report=self.positive()
        self.assertEqual(0,native_exit_status(130,True,report))
        self.assertEqual(65,native_exit_status(65,False,report),'an ordinary build failure is retained')
        report['finalized']=False
        self.assertEqual(130,native_exit_status(130,True,report),'a completed console cannot replace a finalized bundle')
        report=self.positive();report['cases'][0]['status']='failed'
        self.assertEqual(130,native_exit_status(130,True,report),'a failed test cannot be normalized')
    def test_missing_expected_identity(self):
        report=self.positive();report['cases'].pop()
        self.assertEqual('expected-test-not-executed',validate_report(report).first_cause)
    def test_zero_tests(self):
        report=self.positive();report['cases']=[]
        self.assertEqual('expected-test-not-executed',validate_report(report).first_cause)
    def test_skip_failure_and_error(self):
        for status in ['skipped','failed','error']:
            report=self.positive();report['cases'][0]['status']=status
            self.assertEqual('test-not-passed',validate_report(report).first_cause)
    def test_wrong_idiom(self):
        report=self.positive();report['idiom']='pad'
        self.assertEqual('wrong-device-idiom',validate_report(report).first_cause)
    def test_unfinalized(self):
        report=self.positive();report['finalized']=False
        self.assertEqual('result-not-finalized',validate_report(report).first_cause)
    def test_armed_is_not_served(self):
        report=self.positive();report['effects'][0]=dict(scenario='rate-limit',revision=1,armed=True)
        self.assertEqual('fixture-effect-not-observed',validate_report(report).first_cause)
    def test_no_scan_or_lost_receipt(self):
        for index in [1,2]:
            report=self.positive();report['effects'].pop(index)
            self.assertEqual('fixture-effect-not-observed',validate_report(report).first_cause)
    def test_no_positive_navidrome(self):
        for baseline in [{},dict(login=True,albums=0,featureCalls=0),dict(login=True,albums=1,featureCalls=1)]:
            report=self.positive();report['baseline']=baseline
            self.assertEqual('navidrome-positive-control-missing',validate_report(report).first_cause)
    def test_receipt_replayed(self):
        report=self.positive();report['replay']={}
        self.assertEqual('lost-receipt-replay-not-observed',validate_report(report).first_cause)

class AppleExtractionTests(unittest.TestCase):
    def test_finalized_tree_has_exact_suite_and_observed_idiom(self):
        tree=dict(devices=[dict(modelName='iPad Pro',platform='iOS Simulator')],testNodes=[dict(nodeType='Test Case',name=name+'()',nodeIdentifier=expected_suite('ipad')+'/'+name+'()',result='Passed') for name in expected_tests('ipad')])
        report=apple_report('ipad',dict(finishTime=123,totalTestCount=5),tree,[],{},expected_tests('ipad'))
        self.assertIsNone(validate_report(report).first_cause)
        tree['testNodes'][0]['nodeIdentifier']='UnrelatedSuite/'+expected_tests('ipad')[0]+'()'
        report=apple_report('ipad',dict(finishTime=123,totalTestCount=5),tree,[],{},expected_tests('ipad'))
        self.assertEqual('expected-test-not-executed',validate_report(report).first_cause)
    def test_finalized_zero_test_bundle_reports_missing_execution(self):
        report=apple_report('iphone',dict(finishTime=123,totalTestCount=0),dict(devices=[],testNodes=[]),[],{})
        self.assertEqual('expected-test-not-executed',validate_report(report).first_cause)
    def test_unfinished_summary_cannot_use_completed_log(self):
        report=apple_report('iphone',{},dict(devices=[],testNodes=[]),[],{})
        self.assertEqual('result-not-finalized',validate_report(report).first_cause)

if __name__=='__main__':unittest.main()
