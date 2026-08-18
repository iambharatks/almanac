class Solution {
public:
    int minPenalty(int period, vector<int>& lights, vector<int>& arrivalTime) {
        int res = 0;
        int n = size(lights);
        int mx = *max_element(begin(lights),end(lights));
        for(int i : arrivalTime){
            int l = i%period;
            if(l >= mx){
                res= max(res,(period-l));
            }
        }
        return res;
    }
};