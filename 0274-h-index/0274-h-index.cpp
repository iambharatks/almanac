class Solution {
public:
    int hIndex(vector<int>& citations) {
        int count[1001];
        int n = citations.size();
        memset(count,0,sizeof(count));
        for(int &i : citations)
            count[i]++;
        int cumulative = 0;
        for(int i =1000 ; i >=  0 ; i--){
            // if(count[i] == 0) continue;
            cumulative += count[i];
            // cout<<i<<' ';
            if(i <= cumulative)
                return i;
        }
        return 1;
    }
};