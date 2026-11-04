"""DB 장애 제어의 대상 제한과 복구 보장에 대한 읽기/모의 검증."""
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock
from scenario1 import Failure
from scenario4 import DatabaseFault, validate_database_target, percentile

class FaultSafetyTests(unittest.TestCase):
    def test_rejects_existing_payment_environment(self):
        with self.assertRaises(Failure):
            validate_database_target({'project':'ticket-p13','service':'postgres','running':True,'id':'old-db'})

    def test_rejects_originally_stopped_database(self):
        with self.assertRaises(Failure):
            validate_database_target({'project':'ticket-integration','service':'postgres','running':False,'id':'db'})

    def test_restore_uses_exact_container_id_once(self):
        obj=object.__new__(DatabaseFault)
        obj.db_lock=threading.Lock();obj.restore_needed=True;obj.db_id='exact-integration-db-id'
        obj.report={'timeline':{}};obj.save=Mock();obj.docker=Mock()
        obj.restore_database();obj.restore_database()
        obj.docker.assert_called_once_with('start','exact-integration-db-id')
        self.assertFalse(obj.restore_needed)

    def test_restore_flag_survives_start_failure(self):
        obj=object.__new__(DatabaseFault)
        obj.db_lock=threading.Lock();obj.restore_needed=True;obj.db_id='db'
        obj.report={'timeline':{}};obj.save=Mock();obj.docker=Mock(side_effect=Failure('unavailable'))
        with self.assertRaises(Failure):obj.restore_database()
        self.assertTrue(obj.restore_needed)

    def test_cancellation_during_outage_restores_database(self):
        obj=object.__new__(DatabaseFault)
        obj.db_lock=threading.Lock();obj.lock=threading.RLock()
        obj.cancel=Mock();obj.cancel.wait.side_effect=[False,False,True]
        obj.restore_needed=False;obj.db_id='owned-db';obj.process=None
        obj.args=SimpleNamespace(baseline=0,outage=25,recovery=30)
        obj.report={'measurement_windows':{'browse':{'start':0}},'timeline':{},'fault_error':None}
        obj.save=Mock();obj.docker=Mock();obj.baseline_healthy=Mock(return_value=True)
        obj.fault_controller()
        self.assertEqual(obj.docker.call_args_list[0].args,('stop','--time','5','owned-db'))
        self.assertEqual(obj.docker.call_args_list[1].args,('start','owned-db'))
        self.assertFalse(obj.restore_needed)

    def test_recovery_requires_observed_window_not_three_quick_successes(self):
        obj=object.__new__(DatabaseFault)
        obj.args=SimpleNamespace(recovery=20,recovery_window=5,recovery_success_rate=.95,recovery_p95_ms=1000)
        obj.report={'timeline':{'measure_start':90,'stop_begin':95,'query_ready':100},'checks':{},'recovery':{}}
        obj.requests=[{'source':'probe','service':s,'start_epoch':100+i,'end_epoch':100+i+.01,'ok':True,'status':200}
            for s in ('user','performance','seat','booking','payment','fakepg') for i in range(3)]
        obj.recovery_review()
        self.assertFalse(obj.report['checks']['all_probes_recovered'])
        obj.requests.extend({'source':'probe','service':s,'start_epoch':100+i,'end_epoch':100+i+.01,'ok':True,'status':200}
            for s in ('user','performance','seat','booking','payment','fakepg') for i in range(3,5))
        obj.recovery_review()
        self.assertTrue(obj.report['checks']['all_probes_recovered'])

if __name__=='__main__':unittest.main()
